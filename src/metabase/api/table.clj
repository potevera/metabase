(ns metabase.api.table
  "/api/table endpoints."
  (:require
   [clojure.java.io :as io]
   [flatland.ordered.map :as ordered-map]
   [medley.core :as m]
   [metabase.api.common :as api]
   [metabase.api.macros :as api.macros]
   [metabase.driver.h2 :as h2]
   [metabase.driver.util :as driver.u]
   [metabase.events.core :as events]
   [metabase.lib.core :as lib]
   [metabase.lib.metadata :as lib.metadata]
   [metabase.lib.schema.id :as lib.schema.id]
   [metabase.models.field-values :as field-values]
   [metabase.models.interface :as mi]
   [metabase.models.table :as table]
   [metabase.premium-features.core :refer [defenterprise]]
   [metabase.query-processor :as qp]
   [metabase.query-processor.store :as qp.store]
   [metabase.query-processor.streaming :as qp.streaming]
   [metabase.request.core :as request]
   [metabase.sync.core :as sync]
   [metabase.types :as types]
   [metabase.upload.core :as upload]
   [metabase.util :as u]
   [metabase.util.i18n :refer [deferred-tru deferred-trun tru]]
   [metabase.util.log :as log]
   [metabase.util.malli :as mu]
   [metabase.util.malli.schema :as ms]
   [metabase.util.quick-task :as quick-task]
   [metabase.xrays.core :as xrays]
   [steffan-westcott.clj-otel.api.trace.span :as span]
   [toucan2.core :as t2]))

(set! *warn-on-reflection* true)

(def ^:private TableVisibilityType
  "Schema for a valid table visibility type."
  (into [:enum] (map name table/visibility-types)))

(def ^:private FieldOrder
  "Schema for a valid table field ordering."
  (into [:enum] (map name table/field-orderings)))

(defn- present-table
  "Given a table, shape it for the API."
  [table]
  (-> table
      (update :db dissoc :router_database_id)
      (update :schema str)))

(api.macros/defendpoint :get "/"
  "Get all `Tables`."
  []
  (as-> (t2/select :model/Table, :active true, {:order-by [[:name :asc]]}) tables
    (t2/hydrate tables :db)
    (into [] (comp (filter mi/can-read?)
                   (map present-table))
          tables)))

(api.macros/defendpoint :get "/:id"
  "Get `Table` with ID."
  [{:keys [id]} :- [:map
                    [:id ms/PositiveInt]]
   {:keys [include_editable_data_model]}
   :- [:map
       [:include_editable_data_model {:optional true} [:maybe :boolean]]]]
  (let [api-perm-check-fn (if include_editable_data_model
                            api/write-check
                            api/read-check)]
    (-> (api-perm-check-fn :model/Table id)
        (t2/hydrate :db :pk_field)
        present-table)))

(api.macros/defendpoint :get "/:table-id/data"
  "Get the data for the given table"
  [{:keys [table-id]} :- [:map [:table-id ms/PositiveInt]]]
  (let [table (t2/select-one :model/Table :id table-id)
        db-id (:db_id table)]
    (api/read-check table)
    (qp.store/with-metadata-provider db-id
      (let [mp       (qp.store/metadata-provider)
            query    (-> (lib/query mp (lib.metadata/table mp table-id))
                         (update-in [:middleware :js-int-to-string?] (fnil identity true))
                         qp/userland-query-with-default-constraints
                         (update :info merge {:executed-by api/*current-user-id*
                                              :context     :table-grid
                                              :card-id     nil}))]
        (events/publish-event! :event/table-read {:object  table
                                                  :user-id api/*current-user-id*})
        (span/with-span!
          {:name "query-table-async"}
          (qp.streaming/streaming-response [rff :api]
            (qp/process-query query
             ;; For now, doing this transformation here makes it easy to iterate on our payload shape.
             ;; In the future, we might want to implement a new export-type, say `:api/table`, instead.
             ;; Then we can avoid building non-relevant fields, only to throw them away again.
                              (qp.streaming/transforming-query-response
                               rff
                               (fn [response]
                                 (dissoc response :json_query :context :cached :average_execution_time))))))))))

(mu/defn ^:private update-table!*
  "Takes an existing table and the changes, updates in the database and optionally calls `table/update-field-positions!`
  if field positions have changed."
  [{:keys [id] :as existing-table} :- [:map [:id ::lib.schema.id/table]]
   body]
  (when-let [changes (not-empty (u/select-keys-when body
                                                    :non-nil [:display_name :show_in_getting_started :entity_type :field_order]
                                                    :present [:description :caveats :points_of_interest :visibility_type]))]
    (api/check-500 (pos? (t2/update! :model/Table id changes))))
  (let [updated-table        (t2/select-one :model/Table :id id)
        changed-field-order? (not= (:field_order updated-table) (:field_order existing-table))]
    (if changed-field-order?
      (do
        (table/update-field-positions! updated-table)
        (t2/hydrate updated-table [:fields [:target :has_field_values] :dimensions :has_field_values]))
      updated-table)))

;; TODO -- this seems like it belongs in the `sync` module... right?
(defn- sync-unhidden-tables
  "Function to call on newly unhidden tables. Starts a thread to sync all tables."
  [newly-unhidden]
  (when (seq newly-unhidden)
    (quick-task/submit-task!
     (fn []
       (let [database (table/database (first newly-unhidden))]
         ;; it's okay to allow testing H2 connections during sync. We only want to disallow you from testing them for the
         ;; purposes of creating a new H2 database.
         (if (binding [h2/*allow-testing-h2-connections* true]
               (driver.u/can-connect-with-details? (:engine database) (:details database)))
           (doseq [table newly-unhidden]
             (log/info (u/format-color :green "Table '%s' is now visible. Resyncing." (:name table)))
             (sync/sync-table! table))
           (log/warn (u/format-color :red "Cannot connect to database '%s' in order to sync unhidden tables"
                                     (:name database)))))))))

(defn- update-tables!
  [ids {:keys [visibility_type] :as body}]
  (let [existing-tables (t2/select :model/Table :id [:in ids])]
    (api/check-404 (= (count existing-tables) (count ids)))
    (run! api/write-check existing-tables)
    (let [updated-tables (t2/with-transaction [_conn] (mapv #(update-table!* % body) existing-tables))
          newly-unhidden (when (and (contains? body :visibility_type) (nil? visibility_type))
                           (into [] (filter (comp some? :visibility_type)) existing-tables))]
      (sync-unhidden-tables newly-unhidden)
      updated-tables)))

(api.macros/defendpoint :put "/:id"
  "Update `Table` with ID."
  [{:keys [id]} :- [:map
                    [:id ms/PositiveInt]]
   _query-params
   body :- [:map
            [:display_name            {:optional true} [:maybe ms/NonBlankString]]
            [:entity_type             {:optional true} [:maybe ms/EntityTypeKeywordOrString]]
            [:visibility_type         {:optional true} [:maybe TableVisibilityType]]
            [:description             {:optional true} [:maybe :string]]
            [:caveats                 {:optional true} [:maybe :string]]
            [:points_of_interest      {:optional true} [:maybe :string]]
            [:show_in_getting_started {:optional true} [:maybe :boolean]]
            [:field_order             {:optional true} [:maybe FieldOrder]]]]
  (first (update-tables! [id] body)))

(api.macros/defendpoint :put "/"
  "Update all `Table` in `ids`."
  [_route-params
   _query-params
   {:keys [ids], :as body} :- [:map
                               [:ids                     [:sequential ms/PositiveInt]]
                               [:display_name            {:optional true} [:maybe ms/NonBlankString]]
                               [:entity_type             {:optional true} [:maybe ms/EntityTypeKeywordOrString]]
                               [:visibility_type         {:optional true} [:maybe TableVisibilityType]]
                               [:description             {:optional true} [:maybe :string]]
                               [:caveats                 {:optional true} [:maybe :string]]
                               [:points_of_interest      {:optional true} [:maybe :string]]
                               [:show_in_getting_started {:optional true} [:maybe :boolean]]]]
  (update-tables! ids body))

(def ^:private auto-bin-str (deferred-tru "Auto bin"))
(def ^:private dont-bin-str (deferred-tru "Don''t bin"))

;;; Apparently `msgcat` is not cool with us using a string as both a singular message ID and a plural message ID, and
;;; since we're using stuff like `Minute` as a plural string elsewhere (see [[metabase.lib.temporal-bucket]]) we're
;;; forced to use `*-trun` here as well
(def ^:private unit->deferred-i18n-description
  {:minute          (deferred-trun "Minute" "Minutes" 1)
   :hour            (deferred-trun "Hour" "Hours" 1)
   :day             (deferred-trun "Day" "Days" 1)
   :week            (deferred-trun "Week" "Weeks" 1)
   :month           (deferred-trun "Month" "Months" 1)
   :quarter         (deferred-trun "Quarter" "Quarters" 1)
   :year            (deferred-trun "Year" "Years" 1)
   :minute-of-hour  (deferred-trun "Minute of hour" "Minutes of hour" 1)
   :hour-of-day     (deferred-trun "Hour of day" "Hours of day" 1)
   :day-of-week     (deferred-trun "Day of week" "Days of week" 1)
   :day-of-month    (deferred-trun "Day of month" "Days of month" 1)
   :day-of-year     (deferred-trun "Day of year" "Days of year" 1)
   :week-of-year    (deferred-trun "Week of year" "Weeks of year" 1)
   :month-of-year   (deferred-trun "Month of year" "Months of year" 1)
   :quarter-of-year (deferred-trun "Quarter of year" "Quarters of year" 1)})

;; note the order of these options corresponds to the order they will be shown to the user in the UI
(def ^:private time-options
  (mapv (fn [unit]
          [(unit->deferred-i18n-description unit) (name unit)])
        [:minute :hour :minute-of-hour]))

(def ^:private datetime-options
  (mapv (fn [unit]
          [(unit->deferred-i18n-description unit) (name unit)])
        [:minute
         :hour
         :day
         :week
         :month
         :quarter
         :year
         :minute-of-hour
         :hour-of-day
         :day-of-week
         :day-of-month
         :day-of-year
         :week-of-year
         :month-of-year
         :quarter-of-year]))

(def ^:private date-options
  (mapv (fn [unit]
          [(unit->deferred-i18n-description unit) (name unit)])
        [:day
         :week
         :month
         :quarter
         :year
         :day-of-week
         :day-of-month
         :day-of-year
         :week-of-year
         :month-of-year
         :quarter-of-year]))

(def ^:private dimension-options
  (let [default-entry [auto-bin-str ["default"]]]
    (into (ordered-map/ordered-map)
          (comp cat
                (map-indexed vector))
          [(map (fn [[name param]]
                  {:name name
                   :mbql [:field nil {:temporal-unit param}]
                   :type :type/Date})
                date-options)
           (map (fn [[name param]]
                  {:name name
                   :mbql [:field nil {:temporal-unit param}]
                   :type :type/DateTime})
                datetime-options)
           (map (fn [[name param]]
                  {:name name
                   :mbql [:field nil {:temporal-unit param}]
                   :type :type/Time})
                time-options)
           (map (fn [[name [strategy param]]]
                  {:name name
                   :mbql [:field nil {:binning (merge {:strategy strategy}
                                                      (when param
                                                        {strategy param}))}]
                   :type :type/Number})
                [default-entry
                 [(deferred-tru "10 bins") ["num-bins" 10]]
                 [(deferred-tru "50 bins") ["num-bins" 50]]
                 [(deferred-tru "100 bins") ["num-bins" 100]]])
           [{:name dont-bin-str
             :mbql nil
             :type :type/Number}]
           (map (fn [[name [strategy param]]]
                  {:name name
                   :mbql [:field nil {:binning (merge {:strategy strategy}
                                                      (when param
                                                        {strategy param}))}]
                   :type :type/Coordinate})
                [default-entry
                 [(deferred-tru "Bin every 0.1 degrees") ["bin-width" 0.1]]
                 [(deferred-tru "Bin every 1 degree") ["bin-width" 1.0]]
                 [(deferred-tru "Bin every 10 degrees") ["bin-width" 10.0]]
                 [(deferred-tru "Bin every 20 degrees") ["bin-width" 20.0]]])
           [{:name dont-bin-str
             :mbql nil
             :type :type/Coordinate}]])))

(def ^:private dimension-options-for-response
  (m/map-keys str dimension-options))

(defn- create-dim-index-seq [dim-type]
  (->> dimension-options
       (m/filter-vals (fn [v] (= (:type v) dim-type)))
       keys
       sort
       (map str)))

(def ^:private datetime-dimension-indexes
  (create-dim-index-seq :type/DateTime))

(def ^:private time-dimension-indexes
  (create-dim-index-seq :type/Time))

(def ^:private date-dimension-indexes
  (create-dim-index-seq :type/Date))

(def ^:private numeric-dimension-indexes
  (create-dim-index-seq :type/Number))

(def ^:private coordinate-dimension-indexes
  (create-dim-index-seq :type/Coordinate))

(defn- dimension-index-for-type [dim-type pred]
  (let [dim' (keyword dim-type)]
    (first (m/find-first (fn [[_k v]]
                           (and (= dim' (:type v))
                                (pred v))) dimension-options-for-response))))

(def ^:private datetime-default-index
  (dimension-index-for-type :type/DateTime #(= (str (unit->deferred-i18n-description :day)) (str (:name %)))))

(def ^:private date-default-index
  (dimension-index-for-type :type/Date #(= (str (unit->deferred-i18n-description :day)) (str (:name %)))))

(def ^:private time-default-index
  (dimension-index-for-type :type/Time #(= (str (unit->deferred-i18n-description :hour)) (str (:name %)))))

(def ^:private numeric-default-index
  (dimension-index-for-type :type/Number #(.contains ^String (str (:name %)) (str auto-bin-str))))

(def ^:private coordinate-default-index
  (dimension-index-for-type :type/Coordinate #(.contains ^String (str (:name %)) (str auto-bin-str))))

(defn- supports-numeric-binning? [db]
  (and db (driver.u/supports? (:engine db) :binning db)))

;; TODO: Remove all this when the FE is fully ported to [[metabase.lib.binning/available-binning-strategies]].
(defn- assoc-field-dimension-options [{:keys [base_type semantic_type fingerprint] :as field} db]
  (let [{min_value :min, max_value :max} (get-in fingerprint [:type :type/Number])
        [default-option all-options] (cond
                                       (types/field-is-type? :type/Time field)
                                       [time-default-index time-dimension-indexes]

                                       (types/field-is-type? :type/Date field)
                                       [date-default-index date-dimension-indexes]

                                       (types/temporal-field? field)
                                       [datetime-default-index datetime-dimension-indexes]

                                       (and min_value max_value
                                            (isa? semantic_type :type/Coordinate)
                                            (supports-numeric-binning? db))
                                       [coordinate-default-index coordinate-dimension-indexes]

                                       (and min_value max_value
                                            (isa? base_type :type/Number)
                                            (not (isa? semantic_type :Relation/*))
                                            (supports-numeric-binning? db))
                                       [numeric-default-index numeric-dimension-indexes]

                                       :else
                                       [nil []])]
    (assoc field
           :default_dimension_option default-option
           :dimension_options        all-options)))

(defn- assoc-dimension-options [resp db]
  (-> resp
      (assoc :dimension_options dimension-options-for-response)
      (update :fields (fn [fields]
                        (mapv #(assoc-field-dimension-options % db) fields)))))

(defn- format-fields-for-response [resp]
  (update resp :fields
          (fn [fields]
            (for [{:keys [values] :as field} fields]
              (if (seq values)
                (update field :values field-values/field-values->pairs)
                field)))))

(defn fetch-query-metadata*
  "Returns the query metadata used to power the Query Builder for the given `table`. `include-sensitive-fields?`,
  `include-hidden-fields?` and `include-editable-data-model?` can be either booleans or boolean strings."
  [table {:keys [include-sensitive-fields? include-hidden-fields? include-editable-data-model?]}]
  (if include-editable-data-model?
    (api/write-check table)
    (api/read-check table))
  (let [db (t2/select-one :model/Database :id (:db_id table))]
    (-> table
        (t2/hydrate :db [:fields [:target :has_field_values] :has_field_values :dimensions :name_field] :segments :metrics)
        (m/dissoc-in [:db :details])
        (assoc-dimension-options db)
        format-fields-for-response
        present-table
        (update :fields (partial filter (fn [{visibility-type :visibility_type}]
                                          (case (keyword visibility-type)
                                            :hidden    include-hidden-fields?
                                            :sensitive include-sensitive-fields?
                                            true)))))))

(defn batch-fetch-query-metadatas*
  "Returns the query metadata used to power the Query Builder for the `table`s specified by `ids`."
  [ids]
  (when (seq ids)
    (let [tables (->> (t2/select :model/Table :id [:in ids])
                      (filter mi/can-read?))
          tables (t2/hydrate tables
                             [:fields [:target :has_field_values] :has_field_values :dimensions :name_field]
                             :segments
                             :metrics)]
      (for [table tables]
        (-> table
            (m/dissoc-in [:db :details])
            format-fields-for-response
            present-table
            (update :fields #(remove (comp #{:hidden :sensitive} :visibility_type) %)))))))

(defenterprise fetch-table-query-metadata
  "Returns the query metadata used to power the Query Builder for the given table `id`. `include-sensitive-fields?`,
  `include-hidden-fields?` and `include-editable-data-model?` can be either booleans or boolean strings."
  metabase-enterprise.sandbox.api.table
  [id opts]
  (fetch-query-metadata* (t2/select-one :model/Table :id id) opts))

(defenterprise batch-fetch-table-query-metadatas
  "Returns the query metadatas used to power the Query Builder for the tables specified by `ids`."
  metabase-enterprise.sandbox.api.table
  [ids]
  (batch-fetch-query-metadatas* ids))

(api.macros/defendpoint :get "/:id/query_metadata"
  "Get metadata about a `Table` useful for running queries.
   Returns DB, fields, field FKs, and field values.

   Passing `include_hidden_fields=true` will include any hidden `Fields` in the response. Defaults to `false`
   Passing `include_sensitive_fields=true` will include any sensitive `Fields` in the response. Defaults to `false`.

   Passing `include_editable_data_model=true` will check that the current user has write permissions for the table's
   data model, while `false` checks that they have data access perms for the table. Defaults to `false`.

   These options are provided for use in the Admin Edit Metadata page."
  [{:keys [id]} :- [:map
                    [:id ms/PositiveInt]]
   {:keys [include_sensitive_fields include_hidden_fields include_editable_data_model]}
   :- [:map
       [:include_sensitive_fields    {:default false} [:maybe ms/BooleanValue]]
       [:include_hidden_fields       {:default false} [:maybe ms/BooleanValue]]
       [:include_editable_data_model {:default false} [:maybe ms/BooleanValue]]]]
  (fetch-table-query-metadata id {:include-sensitive-fields?    include_sensitive_fields
                                  :include-hidden-fields?       include_hidden_fields
                                  :include-editable-data-model? include_editable_data_model}))

(defn- card-result-metadata->virtual-fields
  "Return a sequence of 'virtual' fields metadata for the 'virtual' table for a Card in the Saved Questions 'virtual'
   database.
  `metadata-fields` can be nil."
  [card-id database-or-id metadata metadata-fields]
  (let [db (cond->> database-or-id
             (int? database-or-id) (t2/select-one :model/Database :id))
        underlying (m/index-by :id (or metadata-fields
                                       (when-let [ids (seq (keep :id metadata))]
                                         (-> (t2/select :model/Field :id [:in ids])
                                             (t2/hydrate [:target :has_field_values] :has_field_values :dimensions :name_field)))))
        fields (for [{col-id :id :as col} metadata]
                 (-> col
                     (update :base_type keyword)
                     (merge (select-keys (underlying col-id)
                                         [:semantic_type :fk_target_field_id :has_field_values :target :dimensions :name_field]))
                     (assoc
                      :table_id     (str "card__" card-id)
                      :id           (or col-id
                                        ;; TODO -- what????
                                        [:field (:name col) {:base-type (or (:base_type col) :type/*)}])
                      ;; Assoc semantic_type at least temprorarily. We need the correct semantic type in place to make decisions
                      ;; about what kind of dimension options should be added. PK/FK values will be removed after we've added
                      ;; the dimension options
                      :semantic_type (keyword (:semantic_type col)))
                     (assoc-field-dimension-options db)))]
    fields))

(defn root-collection-schema-name
  "Schema name to use for the saved questions virtual database for Cards that are in the root collection (i.e., not in
  any collection)."
  []
  "Everything else")

(defn card->virtual-table
  "Return metadata for a 'virtual' table for a `card` in the Saved Questions 'virtual' database. Optionally include
  'virtual' fields as well."
  [{:keys [database_id] :as card} & {:keys [include-fields? databases card-id->metadata-fields]}]
  ;; if collection isn't already hydrated then do so
  (let [card-type (:type card)
        dataset-query (:dataset_query card)]
    (cond-> {:id               (str "card__" (u/the-id card))
             :db_id            (:database_id card)
             :display_name     (:name card)
             :schema           (get-in card [:collection :name] (root-collection-schema-name))
             :moderated_status (:moderated_status card)
             :description      (:description card)
             :entity_id        (:entity_id card)
             :metrics          (:metrics card)
             :type             card-type}
      (and (= card-type :metric)
           dataset-query)
      (assoc :dataset_query dataset-query)

      include-fields?
      (assoc :fields (card-result-metadata->virtual-fields (u/the-id card)
                                                           (cond-> database_id
                                                             databases databases)
                                                           (:result_metadata card)
                                                           (when card-id->metadata-fields
                                                             (card-id->metadata-fields (u/the-id card))))))))

(defn- remove-nested-pk-fk-semantic-types
  "This method clears the semantic_type attribute for PK/FK fields of nested queries. Those fields having a semantic
  type confuses the frontend and it can really used in the same way"
  [{:keys [fields] :as metadata-response} {:keys [trust-semantic-keys?]}]
  (assoc metadata-response :fields (for [{:keys [semantic_type id] :as field} fields]
                                     (if (and (not trust-semantic-keys?)
                                              (or (isa? semantic_type :type/PK)
                                                  (isa? semantic_type :type/FK))
                                              ;; if they have a user entered id let it stay
                                              (or (nil? id)
                                                  (not (number? id))))
                                       (assoc field :semantic_type nil)
                                       field))))

(defn batch-fetch-card-query-metadatas
  "Return metadata for the 'virtual' tables for a Cards.
  Unreadable cards are silently skipped."
  [ids]
  (when (seq ids)
    (let [cards (t2/select :model/Card
                           {:select    [:c.id :c.dataset_query :c.result_metadata :c.name
                                        :c.description :c.collection_id :c.database_id :c.type
                                        :c.source_card_id :c.created_at :c.entity_id :c.card_schema
                                        [:r.status :moderated_status]]
                            :from      [[:report_card :c]]
                            :left-join [[{:select   [:moderated_item_id :status]
                                          :from     [:moderation_review]
                                          :where    [:and
                                                     [:= :moderated_item_type "card"]
                                                     [:= :most_recent true]]
                                          :order-by [[:id :desc]]
                                          :limit    1} :r]
                                        [:= :r.moderated_item_id :c.id]]
                            :where      [:in :c.id ids]})
          dbs (if (seq cards)
                (t2/select-pk->fn identity :model/Database :id [:in (into #{} (map :database_id) cards)])
                {})
          metadata-field-ids (into #{}
                                   (comp (mapcat :result_metadata)
                                         (keep :id))
                                   cards)
          metadata-fields (if (seq metadata-field-ids)
                            (-> (t2/select :model/Field :id [:in metadata-field-ids])
                                (t2/hydrate [:target :has_field_values] :has_field_values :dimensions :name_field)
                                (->> (m/index-by :id)))
                            {})
          card-id->metadata-fields (into {}
                                         (map (fn [card]
                                                [(:id card) (into []
                                                                  (keep (comp metadata-fields :id))
                                                                  (:result_metadata card))]))
                                         cards)
          readable-cards (t2/hydrate (filter mi/can-read? cards) :metrics)]
      (for [card readable-cards]
        ;; a native model can have columns with keys as semantic types only if a user configured them
        (let [trust-semantic-keys? (and (= (:type card) :model)
                                        (= (-> card :dataset_query :type) :native))]
          (-> card
              (card->virtual-table :include-fields? true
                                   :databases dbs
                                   :card-id->metadata-fields card-id->metadata-fields)
              (assoc-dimension-options (-> card :database_id dbs))
              (remove-nested-pk-fk-semantic-types {:trust-semantic-keys? trust-semantic-keys?})))))))

(api.macros/defendpoint :get "/card__:id/query_metadata"
  "Return metadata for the 'virtual' table for a Card."
  [{:keys [id]} :- [:map
                    [:id ms/PositiveInt]]]
  (first (batch-fetch-card-query-metadatas [id])))

(api.macros/defendpoint :get "/card__:id/fks"
  "Return FK info for the 'virtual' table for a Card. This is always empty, so this endpoint
   serves mainly as a placeholder to avoid having to change anything on the frontend."
  [_route-params :- [:map
                     [:id ms/PositiveInt]]]
  []) ; return empty array

(api.macros/defendpoint :get "/:id/fks"
  "Get all foreign keys whose destination is a `Field` that belongs to this `Table`."
  [{:keys [id]} :- [:map
                    [:id ms/PositiveInt]]]
  (api/read-check :model/Table id)
  (when-let [field-ids (seq (t2/select-pks-set :model/Field, :table_id id, :visibility_type [:not= "retired"], :active true))]
    (for [origin-field (t2/select :model/Field, :fk_target_field_id [:in field-ids], :active true)]
      ;; it's silly to be hydrating some of these tables/dbs
      {:relationship   :Mt1
       :origin_id      (:id origin-field)
       :origin         (-> (t2/hydrate origin-field [:table :db])
                           (update :table present-table))
       :destination_id (:fk_target_field_id origin-field)
       :destination    (t2/hydrate (t2/select-one :model/Field :id (:fk_target_field_id origin-field)) :table)})))

(api.macros/defendpoint :post "/:id/rescan_values"
  "Manually trigger an update for the FieldValues for the Fields belonging to this Table. Only applies to Fields that
   are eligible for FieldValues."
  [{:keys [id]} :- [:map
                    [:id ms/PositiveInt]]]
  (let [table (api/write-check (t2/select-one :model/Table :id id))]
    (events/publish-event! :event/table-manual-scan {:object table :user-id api/*current-user-id*})
    ;; Grant full permissions so that permission checks pass during sync. If a user has DB detail perms
    ;; but no data perms, they should stll be able to trigger a sync of field values. This is fine because we don't
    ;; return any actual field values from this API. (#21764)
    (request/as-admin
      ;; async so as not to block the UI
      (quick-task/submit-task!
       (fn []
         (sync/update-field-values-for-table! table))))
    {:status :success}))

(api.macros/defendpoint :post "/:id/discard_values"
  "Discard the FieldValues belonging to the Fields in this Table. Only applies to fields that have FieldValues. If
   this Table's Database is set up to automatically sync FieldValues, they will be recreated during the next cycle."
  [{:keys [id]} :- [:map
                    [:id ms/PositiveInt]]]
  (api/write-check (t2/select-one :model/Table :id id))
  (when-let [field-ids (t2/select-pks-set :model/Field :table_id id)]
    (t2/delete! (t2/table-name :model/FieldValues) :field_id [:in field-ids]))
  {:status :success})

(api.macros/defendpoint :get "/:id/related"
  "Return related entities."
  [{:keys [id]} :- [:map
                    [:id ms/PositiveInt]]]
  (-> (t2/select-one :model/Table :id id) api/read-check xrays/related))

(api.macros/defendpoint :put "/:id/fields/order" :- [:map
                                                     [:success [:= true]]]
  "Reorder fields"
  [{:keys [id]} :- [:map
                    [:id ms/PositiveInt]]
   _query-params
   field-order :- [:sequential ms/PositiveInt]]
  (-> (t2/select-one :model/Table :id id) api/write-check (table/custom-order-fields! field-order))
  {:success true})

(mu/defn- update-csv!
  "This helper function exists to make testing the POST /api/table/:id/{action}-csv endpoints easier."
  [options :- [:map
               [:table-id ms/PositiveInt]
               [:filename :string]
               [:file (ms/InstanceOfClass java.io.File)]
               [:action upload/update-action-schema]]]
  (try
    (let [_result (upload/update-csv! options)]
      {:status 200
       ;; There is scope to return something more interesting.
       :body   nil})
    (catch Throwable e
      {:status (or (-> e ex-data :status-code)
                   500)
       :body   {:message (or (ex-message e)
                             (tru "There was an error uploading the file"))}})
    (finally (io/delete-file (:file options) :silently))))

(api.macros/defendpoint :post "/:id/append-csv"
  "Inserts the rows of an uploaded CSV file into the table identified by `:id`. The table must have been created by
  uploading a CSV file."
  {:multipart true}
  [{:keys [id]} :- [:map
                    [:id ms/PositiveInt]]
   _query-params
   _body
   {:keys [multipart-params], :as _request} :- [:map
                                                [:multipart-params
                                                 [:map
                                                  ["file"
                                                   [:map
                                                    [:filename :string]
                                                    [:tempfile (ms/InstanceOfClass java.io.File)]]]]]]]
  (update-csv! {:table-id id
                :filename (get-in multipart-params ["file" :filename])
                :file     (get-in multipart-params ["file" :tempfile])
                :action   :metabase.upload/append}))

(api.macros/defendpoint :post "/:id/replace-csv"
  "Replaces the contents of the table identified by `:id` with the rows of an uploaded CSV file. The table must have
  been created by uploading a CSV file."
  {:multipart true}
  [{:keys [id]} :- [:map
                    [:id ms/PositiveInt]]
   _query-params
   _body
   {:keys [multipart-params], :as _request} :- [:map
                                                [:multipart-params
                                                 [:map
                                                  ["file"
                                                   [:map
                                                    [:filename :string]
                                                    [:tempfile (ms/InstanceOfClass java.io.File)]]]]]]]
  (update-csv! {:table-id id
                :filename (get-in multipart-params ["file" :filename])
                :file     (get-in multipart-params ["file" :tempfile])
                :action   :metabase.upload/replace}))
