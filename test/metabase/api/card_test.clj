(ns metabase.api.card-test
  "Tests for /api/card endpoints."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [clojure.test :refer :all]
   [clojure.tools.macro :as tools.macro]
   [clojurewerkz.quartzite.scheduler :as qs]
   [dk.ative.docjure.spreadsheet :as spreadsheet]
   [java-time :as t]
   [medley.core :as m]
   [metabase.api.card :as api.card]
   [metabase.api.pivots :as api.pivots]
   [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
   [metabase.http-client :as client]
   [metabase.models
    :refer [Card
            CardBookmark
            Collection
            Dashboard
            Database
            ModerationReview
            PersistedInfo
            Pulse
            PulseCard
            PulseChannel
            PulseChannelRecipient
            Table
            Timeline
            TimelineEvent
            ViewLog]]
   [metabase.models.moderation-review :as moderation-review]
   [metabase.models.permissions :as perms]
   [metabase.models.permissions-group :as perms-group]
   [metabase.models.revision :as revision :refer [Revision]]
   [metabase.models.user :refer [User]]
   [metabase.query-processor :as qp]
   [metabase.query-processor.async :as qp.async]
   [metabase.query-processor.card :as qp.card]
   [metabase.query-processor.middleware.constraints :as qp.constraints]
   [metabase.server.middleware.util :as mw.util]
   [metabase.task :as task]
   [metabase.task.persist-refresh :as task.persist-refresh]
   [metabase.task.sync-databases :as task.sync-databases]
   [metabase.test :as mt]
   [metabase.test.data.users :as test.users]
   [metabase.util :as u]
   [metabase.util.schema :as su]
   [schema.core :as s]
   [toucan.db :as db]
   [toucan.hydrate :refer [hydrate]])
  (:import
   (java.io ByteArrayInputStream)
   (java.util UUID)
   (org.quartz.impl StdSchedulerFactory)))

(comment api.card/keep-me)

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                              Helper Fns & Macros                                               |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- count-base-type []
  (-> (mt/run-mbql-query venues {:aggregation [[:count]]}) :data :cols first :base_type))

(def card-defaults
  "The default card params."
  {:archived            false
   :collection_id       nil
   :collection_position nil
   :collection_preview  true
   :dataset_query       {}
   :dataset             false
   :description         nil
   :display             "scalar"
   :enable_embedding    false
   :entity_id           nil
   :embedding_params    nil
   :made_public_by_id   nil
   :parameters          []
   :parameter_mappings  []
   :moderation_reviews  ()
   :public_uuid         nil
   :query_type          nil
   :cache_ttl           nil
   :average_query_time  nil
   :last_query_start    nil
   :result_metadata     nil})

;; Used in dashboard tests
(def card-defaults-no-hydrate
  (dissoc card-defaults :average_query_time :last_query_start))

(defn mbql-count-query
  ([]
   (mbql-count-query (mt/id) (mt/id :venues)))

  ([db-or-id table-or-id]
   {:database (u/the-id db-or-id)
    :type     :query
    :query    {:source-table (u/the-id table-or-id), :aggregation [[:count]]}}))

(defn card-with-name-and-query
  ([]
   (card-with-name-and-query (mt/random-name)))

  ([card-name]
   (card-with-name-and-query card-name (mbql-count-query)))

  ([card-name query]
   {:name                   card-name
    :display                "scalar"
    :dataset_query          query
    :visualization_settings {:global {:title nil}}}))

(defn- do-with-temp-native-card
  {:style/indent 0}
  [f]
  (mt/with-temp* [Database   [db    {:details (:details (mt/db)), :engine :h2}]
                  Table      [_     {:db_id (u/the-id db), :name "CATEGORIES"}]
                  Card       [card  {:dataset_query {:database (u/the-id db)
                                                     :type     :native
                                                     :native   {:query "SELECT COUNT(*) FROM CATEGORIES;"}}}]]
    (f db card)))

(defmacro ^:private with-temp-native-card
  {:style/indent 1}
  [[db-binding card-binding] & body]
  `(do-with-temp-native-card (fn [~(or db-binding '_) ~(or card-binding '_)]
                               ~@body)))

(defn do-with-cards-in-a-collection [card-or-cards-or-ids grant-perms-fn! f]
  (mt/with-non-admin-groups-no-root-collection-perms
    (mt/with-temp Collection [collection]
      ;; put all the Card(s) in our temp `collection`
      (doseq [card-or-id (if (sequential? card-or-cards-or-ids)
                           card-or-cards-or-ids
                           [card-or-cards-or-ids])]
        (db/update! Card (u/the-id card-or-id) {:collection_id (u/the-id collection)}))
      ;; now use `grant-perms-fn!` to grant appropriate perms
      (grant-perms-fn! (perms-group/all-users) collection)
      ;; call (f)
      (f))))

(defmacro with-cards-in-readable-collection
  "Execute `body` with `card-or-cards-or-ids` added to a temporary Collection that All Users have read permissions for."
  {:style/indent 1}
  [card-or-cards-or-ids & body]
  `(do-with-cards-in-a-collection ~card-or-cards-or-ids perms/grant-collection-read-permissions! (fn [] ~@body)))

(defmacro with-cards-in-writeable-collection
  "Execute `body` with `card-or-cards-or-ids` added to a temporary Collection that All Users have *write* permissions
  for."
  {:style/indent 1}
  [card-or-cards-or-ids & body]
  `(do-with-cards-in-a-collection ~card-or-cards-or-ids perms/grant-collection-readwrite-permissions! (fn [] ~@body)))

(defn- do-with-temp-native-card-with-params {:style/indent 0} [f]
  (mt/with-temp*
    [Database   [db    {:details (:details (mt/db)), :engine :h2}]
     Table      [_     {:db_id (u/the-id db), :name "VENUES"}]
     Card       [card  {:dataset_query
                        {:database (u/the-id db)
                         :type     :native
                         :native   {:query         "SELECT COUNT(*) FROM VENUES WHERE CATEGORY_ID = {{category}};"
                                    :template-tags {:category {:id           "_CATEGORY_ID_"
                                                               :name         "category"
                                                               :display_name "Category"
                                                               :type         "number"
                                                               :required     true}}}}}]]
    (f db card)))

(defmacro ^:private with-temp-native-card-with-params {:style/indent 1} [[db-binding card-binding] & body]
  `(do-with-temp-native-card-with-params (fn [~(or db-binding '_) ~(or card-binding '_)] ~@body)))

(deftest run-query-with-parameters-test
  (testing "POST /api/card/:id/query"
    (testing "should respect `:parameters`"
      (with-temp-native-card-with-params [{db-id :id} {card-id :id}]
        (is (schema= {:database_id (s/eq db-id)
                      :row_count   (s/eq 1)
                      :data        {:rows     (s/eq [[8]])
                                    s/Keyword s/Any}
                      s/Keyword    s/Any}
                     (mt/user-http-request
                      :rasta :post 202 (format "card/%d/query" card-id)
                      {:parameters [{:type   :number
                                     :target [:variable [:template-tag :category]]
                                     :value  2}]})))))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                           FETCHING CARDS & FILTERING                                           |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- card-returned? [model object-or-id card-or-id]
  (contains? (set (for [card (mt/user-http-request :rasta :get 200 "card", :f model, :model_id (u/the-id object-or-id))]
                    (u/the-id card)))
             (u/the-id card-or-id)))

(deftest filter-cards-by-db-test
  (mt/with-temp* [Database [db]
                  Card     [card-1 {:database_id (mt/id)}]
                  Card     [card-2 {:database_id (u/the-id db)}]]
    (with-cards-in-readable-collection [card-1 card-2]
      (is (= true
             (card-returned? :database (mt/id) card-1)))
      (is (= false
             (card-returned? :database db        card-1)))
      (is (= true
             (card-returned? :database db        card-2))))))


(deftest authentication-test
  (is (= (get mw.util/response-unauthentic :body) (client/client :get 401 "card")))
  (is (= (get mw.util/response-unauthentic :body) (client/client :put 401 "card/13"))))

(deftest model-id-requied-when-f-is-database-test
  (is (= {:errors {:model_id "model_id is a required parameter when filter mode is 'database'"}}
         (mt/user-http-request :crowberto :get 400 "card" :f :database))))

(deftest filter-cards-by-table-test
  (testing "Filter cards by table"
    (mt/with-temp* [Database [db]
                    Table    [table-1  {:db_id (u/the-id db)}]
                    Table    [table-2  {:db_id (u/the-id db)}]
                    Card     [card-1   {:table_id (u/the-id table-1)}]
                    Card     [card-2   {:table_id (u/the-id table-2)}]]
      (with-cards-in-readable-collection [card-1 card-2]
        (is (= true
               (card-returned? :table (u/the-id table-1) (u/the-id card-1))))
        (is (= false
               (card-returned? :table (u/the-id table-2) (u/the-id card-1))))
        (is (= true
               (card-returned? :table (u/the-id table-2) (u/the-id card-2))))))))

;; Make sure `model_id` is required when `f` is :table
(deftest model_id-requied-when-f-is-table
  (is (= {:errors {:model_id "model_id is a required parameter when filter mode is 'table'"}}
         (mt/user-http-request :crowberto :get 400 "card", :f :table))))

(defn- do-with-card-views [card-or-id+username f]
  (let [[f] (reduce
             (fn [[f timestamp] [card-or-id username]]
               [(fn []
                  (let [card-id   (u/the-id card-or-id)
                        card-name (db/select-one-field :name Card :id card-id)]
                    (testing (format "\nCard %d %s viewed by %s on %s" card-id (pr-str card-name) username timestamp)
                      (mt/with-temp ViewLog [_ {:model     "card"
                                                :model_id  card-id
                                                :user_id   (mt/user->id username)
                                                :timestamp timestamp}]
                        (f)))))
                (t/plus timestamp (t/days 1))])
             [f (t/zoned-date-time)]
             card-or-id+username)]
    (f)))

(defmacro ^:private with-card-views [card-or-id+username & body]
  `(do-with-card-views ~(mapv vec (partition 2 card-or-id+username)) (fn [] ~@body)))

(deftest filter-by-recent-test
  (testing "GET /api/card?f=recent"
    (mt/with-temp* [Card [card-1 {:name "Card 1"}]
                    Card [card-2 {:name "Card 2"}]
                    Card [card-3 {:name "Card 3"}]
                    Card [card-4 {:name "Card 4"}]]
      ;; 3 was viewed most recently, followed by 4, then 1. Card 2 was viewed by a different user so shouldn't be
      ;; returned
      (with-card-views [card-1 :rasta
                        card-2 :trashbird
                        card-3 :rasta
                        card-4 :rasta
                        card-3 :rasta]
        (with-cards-in-readable-collection [card-1 card-2 card-3 card-4]
          (testing "\nShould return cards that were recently viewed by current user only"
            (is (= ["Card 3"
                    "Card 4"
                    "Card 1"]
                   (map :name (mt/user-http-request :rasta :get 200 "card", :f :recent))))))))))

(deftest filter-by-popular-test
  (testing "GET /api/card?f=popular"
    (mt/with-temp* [Card [card-1 {:name "Card 1"}]
                    Card [card-2 {:name "Card 2"}]
                    Card [card-3 {:name "Card 3"}]]
      ;; 3 entries for card 3, 2 for card 2, none for card 1,
      (with-card-views [card-3 :rasta
                        card-2 :trashbird
                        card-2 :rasta
                        card-3 :crowberto
                        card-3 :rasta]
        (with-cards-in-readable-collection [card-1 card-2 card-3]
          (testing (str "`f=popular` should return cards sorted by number of ViewLog entries for all users; cards with "
                        "no entries should be excluded")
            (is (= ["Card 3"
                    "Card 2"]
                   (map :name (mt/user-http-request :rasta :get 200 "card", :f :popular))))))))))

(deftest filter-by-archived-test
  (testing "GET /api/card?f=archived"
    (mt/with-temp* [Card [card-1 {:name "Card 1"}]
                    Card [card-2 {:name "Card 2", :archived true}]
                    Card [card-3 {:name "Card 3", :archived true}]]
      (with-cards-in-readable-collection [card-1 card-2 card-3]
        (is (= #{"Card 2" "Card 3"}
               (set (map :name (mt/user-http-request :rasta :get 200 "card", :f :archived))))
            "The set of Card returned with f=archived should be equal to the set of archived cards")))))

(deftest filter-by-bookmarked-test
  (testing "Filter by `bookmarked`"
    (mt/with-temp* [Card         [card-1 {:name "Card 1"}]
                    Card         [card-2 {:name "Card 2"}]
                    Card         [card-3 {:name "Card 3"}]
                    CardBookmark [_ {:card_id (u/the-id card-1), :user_id (mt/user->id :rasta)}]
                    CardBookmark [_ {:card_id (u/the-id card-2), :user_id (mt/user->id :crowberto)}]]
      (with-cards-in-readable-collection [card-1 card-2 card-3]
        (is (= [{:name "Card 1"}]
               (for [card (mt/user-http-request :rasta :get 200 "card", :f :bookmarked)]
                 (select-keys card [:name]))))))))

(deftest filter-by-using-model
  (testing "list cards using a model"
    (mt/with-temp* [Card [{model-id :id :as model} {:name "Model", :dataset true
                                                    :dataset_query {:query {:source-table 1
                                                                            :filter [:= [:field 1 nil] "1"]}}}]
                    ;; matching question
                    Card [card-1 {:name "Card 1"
                                  :dataset_query {:query {:source-table (str "card__" model-id)}}}]
                    Card [{other-card-id :id}]
                    ;; source-table doesn't match
                    Card [card-2 {:name "Card 2"
                                  :dataset_query {:query {:source-table (str "card__" other-card-id)
                                                          :filter [:= [:field 5 nil] (str "card__" model-id)]}}}]
                    ;; matching join
                    Card [card-3 {:name "Card 3"
                                  :dataset_query (let [alias (str "Question " model-id)]
                                                   {:type :query
                                                    :query {:joins [{:fields [[:field 35 {:join-alias alias}]]
                                                                     :source-table (str "card__" model-id)
                                                                     :condition [:=
                                                                                 [:field 5 nil]
                                                                                 [:field 33 {:join-alias alias}]]
                                                                     :alias alias
                                                                     :strategy :inner-join}]
                                                            :fields [[:field 9 nil]]}
                                                    :database 1})}]
                    ;; matching native query
                    Card [card-4 {:name "Card 4"
                                  :dataset_query {:type :native
                                                  :native (let [model-ref (format "#%d-q1" model-id)]
                                                            {:query (format "select o.id from orders o join {{%s}} q1 on o.PRODUCT_ID = q1.PRODUCT_ID"
                                                                            model-ref)
                                                             :template-tags {model-ref
                                                                             {:id "2185b98b-20b3-65e6-8623-4fb56acb0ca7"
                                                                              :name model-ref
                                                                              :display-name model-ref
                                                                              :type :card
                                                                              :card-id model-id}}})
                                                  :database 1}}]
                    ;; native query reference doesn't match
                    Card [card-5 {:name "Card 5"
                                   :dataset_query {:type :native
                                                   :native (let [model-ref (str "card__" model-id)
                                                                 card-id other-card-id
                                                                 card-ref (format "#%d-q1" card-id)]
                                                             {:query (format "select o.id %s from orders o join {{%s}} q1 on o.PRODUCT_ID = q1.PRODUCT_ID"
                                                                             model-ref card-ref)
                                                              :template-tags {card-ref
                                                                              {:id "2185b98b-20b3-65e6-8623-4fb56acb0ca7"
                                                                               :name card-ref
                                                                               :display-name card-ref
                                                                               :type :card
                                                                               :card-id card-id}}})
                                                   :database 1}}]
                    Database [{other-database-id :id}]
                    ;; database doesn't quite match
                    Card [card-6 {:name "Card 6", :database_id other-database-id
                                   :dataset_query {:query {:source-table (str "card__" model-id)}}}]]
      (with-cards-in-readable-collection [model card-1 card-2 card-3 card-4 card-5 card-6]
        (is (= #{"Card 1" "Card 3" "Card 4"}
               (into #{} (map :name) (mt/user-http-request :rasta :get 200 "card"
                                                           :f :using_model :model_id model-id))))))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                        CREATING A CARD (POST /api/card)                                        |
;;; +----------------------------------------------------------------------------------------------------------------+

(deftest create-a-card
  (testing "POST /api/card"
    (testing "Test that we can create a new Card"
      (mt/with-non-admin-groups-no-root-collection-perms
        (mt/with-temp Collection [collection]
          (perms/grant-collection-readwrite-permissions! (perms-group/all-users) collection)
          (mt/with-model-cleanup [Card]
            (let [card (assoc (card-with-name-and-query (mt/random-name)
                                                        (mbql-count-query (mt/id) (mt/id :venues)))
                              :collection_id      (u/the-id collection)
                              :parameters         [{:id "abc123", :name "test", :type "date"}]
                              :parameter_mappings [{:parameter_id "abc123", :card_id 10,
                                                    :target [:dimension [:template-tags "category"]]}])]
              (is (= (merge
                      card-defaults
                      {:name                   (:name card)
                       :collection_id          true
                       :collection             true
                       :creator_id             (mt/user->id :rasta)
                       :parameters             [{:id "abc123", :name "test", :type "date"}]
                       :parameter_mappings     [{:parameter_id "abc123", :card_id 10,
                                                 :target ["dimension" ["template-tags" "category"]]}]
                       :dataset_query          true
                       :query_type             "query"
                       :visualization_settings {:global {:title nil}}
                       :database_id            true
                       :table_id               true
                       :entity_id              true
                       :can_write              true
                       :dashboard_count        0
                       :result_metadata        true
                       :last-edit-info         {:timestamp true :id true :first_name "Rasta"
                                                :last_name "Toucan" :email "rasta@metabase.com"}
                       :creator                (merge
                                                (select-keys (mt/fetch-user :rasta) [:id :date_joined :last_login :locale])
                                                {:common_name  "Rasta Toucan"
                                                 :is_superuser false
                                                 :last_name    "Toucan"
                                                 :first_name   "Rasta"
                                                 :email        "rasta@metabase.com"})})
                     (-> (mt/user-http-request :rasta :post 200 "card" card)
                         (dissoc :created_at :updated_at :id)
                         (update :table_id integer?)
                         (update :database_id integer?)
                         (update :collection_id integer?)
                         (update :dataset_query map?)
                         (update :collection map?)
                         (update :entity_id string?)
                         (update :result_metadata (partial every? map?))
                         (update :creator dissoc :is_qbnewb)
                         (update :last-edit-info (fn [edit-info]
                                                   (-> edit-info
                                                       (update :id boolean)
                                                       (update :timestamp boolean))))))))))))))

(deftest create-card-validation-test
  (testing "POST /api/card"
    (is (= {:errors {:visualization_settings "value must be a map."}}
           (mt/user-http-request :crowberto :post 400 "card" {:visualization_settings "ABC"})))

    (is (= {:errors {:parameters (str "value may be nil, or if non-nil, value must be an array. "
                                      "Each parameter must be a map with :id and :type keys")}}
           (mt/user-http-request :crowberto :post 400 "card" {:visualization_settings {:global {:title nil}}
                                                              :parameters             "abc"})))
    (with-temp-native-card-with-params [db card]
      (testing "You cannot create a card with variables as a model"
        (is (= "A model made from a native SQL question cannot have a variable or field filter."
               (mt/user-http-request :rasta :post 400 "card"
                                     (merge
                                      (mt/with-temp-defaults Card)
                                      {:dataset       true
                                       :query_type    "native"
                                       :dataset_query (:dataset_query card)})))))
      (testing "You can create a card with a saved question CTE as a model"
        (let [card-tag-name (str "#" (u/the-id card))]
          (mt/user-http-request :rasta :post 200 "card"
                                (merge
                                 (mt/with-temp-defaults Card)
                                 {:dataset_query {:database (u/the-id db)
                                                  :type     :native
                                                  :native   {:query         (format "SELECT * FROM {{%s}};" card-tag-name)
                                                             :template-tags {card-tag-name {:card-id      (u/the-id card),
                                                                                             :display-name card-tag-name,
                                                                                             :id           (str (random-uuid))
                                                                                             :name         card-tag-name,
                                                                                             :type         :card}}}}})))))))

(deftest create-card-disallow-setting-enable-embedding-test
  (testing "POST /api/card"
    (testing "Ignore values of `enable_embedding` while creating a Card (this must be done via `PUT /api/card/:id` instead)"
      ;; should be ignored regardless of the value of the `enable-embedding` Setting.
      (doseq [enable-embedding? [true false]]
        (mt/with-temporary-setting-values [enable-embedding enable-embedding?]
          (mt/with-model-cleanup [Card]
            (is (schema= {:enable_embedding (s/eq false)
                          s/Keyword         s/Any}
                         (mt/user-http-request :crowberto :post 200 "card" {:name                   "My Card"
                                                                            :display                :table
                                                                            :dataset_query          (mt/mbql-query venues)
                                                                            :visualization_settings {}
                                                                            :enable_embedding       true})))))))))

(deftest save-empty-card-test
  (testing "POST /api/card"
    (testing "Should be able to save an empty Card"
      (doseq [[query-description query] {"native query"
                                         (mt/native-query {:query "SELECT * FROM VENUES WHERE false;"})

                                         "MBQL query"
                                         (mt/mbql-query venues {:filter [:= $id 0]})}]
        (testing query-description
          (mt/with-model-cleanup [Card]
            (testing "without result metadata"
              (is (schema= {:id       su/IntGreaterThanZero
                            s/Keyword s/Any}
                           (mt/user-http-request :rasta :post 200 "card"
                                                 (merge (mt/with-temp-defaults Card)
                                                        {:dataset_query query})))))
            (let [metadata (-> (qp/process-query query)
                               :data
                               :results_metadata
                               :columns)]
              (testing (format "with result metadata\n%s" (u/pprint-to-str metadata))
                (is (some? metadata))
                (is (schema= {:id       su/IntGreaterThanZero
                              s/Keyword s/Any}
                             (mt/user-http-request :rasta :post 200 "card"
                                                   (merge (mt/with-temp-defaults Card)
                                                          {:dataset_query   query
                                                           :result_metadata metadata}))))))))))))

(deftest save-card-with-empty-result-metadata-test
  (testing "we should be able to save a Card if the `result_metadata` is *empty* (but not nil) (#9286)"
    (mt/with-model-cleanup [Card]
      (let [card        (card-with-name-and-query)]
        (is (schema= {:id su/IntGreaterThanZero, s/Keyword s/Any}
                     (mt/user-http-request :rasta
                                           :post
                                           200
                                           "card"
                                           (assoc card :result_metadata []))))))))

(deftest cache-ttl-save
  (testing "POST /api/card/:id"
    (testing "saving cache ttl by post actually saves it"
      (mt/with-model-cleanup [Card]
        (let [card        (card-with-name-and-query)]
          (is (= 1234
                 (:cache_ttl (mt/user-http-request :rasta
                                                   :post
                                                   200
                                                   "card"
                                                   (assoc card :cache_ttl 1234)))))))))
  (testing "PUT /api/card/:id"
    (testing "saving cache ttl by put actually saves it"
      (mt/with-temp Card [card]
        (is (= 1234
               (:cache_ttl (mt/user-http-request :rasta
                                     :put
                                     200
                                     (str "card/" (u/the-id card))
                                     {:cache_ttl 1234}))))))
    (testing "nilling out cache ttl works"
      (mt/with-temp Card [card]
        (is (= nil
               (do
                (mt/user-http-request :rasta :put 200 (str "card/" (u/the-id card)) {:cache_ttl 1234})
                (mt/user-http-request :rasta :put 200 (str "card/" (u/the-id card)) {:cache_ttl nil})
                (:cache_ttl (mt/user-http-request :rasta :get 200 (str "card/" (u/the-id card)))))))))))

(deftest saving-card-fetches-correct-metadata
  (testing "make sure when saving a Card the correct query metadata is fetched (if incorrect)"
    (mt/with-non-admin-groups-no-root-collection-perms
      (let [card-name (mt/random-name)]
        (mt/with-temp Collection [collection]
          (perms/grant-collection-readwrite-permissions! (perms-group/all-users) collection)
          (mt/with-model-cleanup [Card]
            (mt/user-http-request :rasta :post 200 "card"
                                  (assoc (card-with-name-and-query card-name)
                                         :collection_id      (u/the-id collection)))
            (testing "check the correct metadata was fetched and was saved in the DB"
              (is (= [{:base_type     :type/BigInteger
                       :display_name  "Count"
                       :name          "count"
                       :semantic_type :type/Quantity
                       :source        :aggregation
                       :field_ref     [:aggregation 0]}]
                     (db/select-one-field :result_metadata Card :name card-name))))))))))

(deftest updating-card-updates-metadata
  (let [query          (mt/mbql-query venues {:fields [$id $name]})
        modified-query (mt/mbql-query venues {:fields [$id $name $price]})
        norm           (comp str/upper-case :name)
        to-native      (fn [q]
                         {:database (:database q)
                          :type     :native
                          :native   (mt/compile q)})]
    (testing "Updating query updates metadata"
      (doseq [[query-type query modified-query] [["mbql"   query modified-query]
                                                 ["native" (to-native query) (to-native modified-query)]]]
        (testing (str "For: " query-type)
          (mt/with-model-cleanup [Card]
            (let [{metadata :result_metadata
                   card-id  :id :as card} (mt/user-http-request
                                           :rasta :post 200
                                           "card"
                                           (card-with-name-and-query "card-name"
                                                                     query))]
              (is (= ["ID" "NAME"] (map norm metadata)))
              ;; simulate a user changing the query without rerunning the query
              (mt/user-http-request
               :rasta :put 200 (str "card/" card-id)
               (assoc card :dataset_query modified-query))
              (is (= ["ID" "NAME" "PRICE"]
                     (map norm (db/select-one-field :result_metadata Card :id card-id)))))))))
    (testing "Updating other parts but not query does not update the metadata"
      (let [orig   qp.async/result-metadata-for-query-async
            called (atom 0)]
        (with-redefs [qp.async/result-metadata-for-query-async (fn [q]
                                                                 (swap! called inc)
                                                                 (orig q))]
          (mt/with-model-cleanup [Card]
            (let [card (mt/user-http-request :rasta :post 200 "card"
                                             (card-with-name-and-query "card-name"
                                                                       query))]
              (is (= @called 1))
              (is (= ["ID" "NAME"] (map norm (:result_metadata card))))
              (mt/user-http-request
               :rasta :put 200 (str "card/" (:id card))
               (assoc card
                      :description "a change that doesn't change the query"
                      :name "compelling title"
                      :cache_ttl 20000
                      :display "table"
                      :collection_position 1))
              (is (= @called 1)))))))
    (testing "Patching the card _without_ the query does not clear the metadata"
      ;; in practice the application does not do this. But cypress does and it poisons the state of the frontend
      (mt/with-model-cleanup [Card]
        (let [card (mt/user-http-request :rasta :post 200 "card"
                                         (card-with-name-and-query "card-name"
                                                                   query))]
          (is (= ["ID" "NAME"] (map norm (:result_metadata card))))
          (let [updated (mt/user-http-request :rasta :put 200 (str "card/" (:id card))
                                              {:description "I'm innocently updating the description"
                                               :dataset true})]
            (is (= ["ID" "NAME"] (map norm (:result_metadata updated))))))))
    (testing "You can update just the metadata"
      (mt/with-model-cleanup [Card]
        (let [card (mt/user-http-request :rasta :post 200 "card"
                                         (card-with-name-and-query "card-name"
                                                                   query))]
          (is (= ["ID" "NAME"] (map norm (:result_metadata card))))
          (let [new-metadata (map #(assoc % :display_name "UPDATED") (:result_metadata card))
                updated (mt/user-http-request :rasta :put 200 (str "card/" (:id card))
                                              {:result_metadata new-metadata})]
            (is (= ["UPDATED" "UPDATED"]
                   (map :display_name (:result_metadata updated))))))))))

(deftest fetch-results-metadata-test
  (testing "Check that the generated query to fetch the query result metadata includes user information in the generated query"
    (mt/with-non-admin-groups-no-root-collection-perms
      (let [card-name (mt/random-name)]
        (mt/with-temp Collection [collection]
          (perms/grant-collection-readwrite-permissions! (perms-group/all-users) collection)
          (mt/with-model-cleanup [Card]
            ;; Rebind the `execute-statement!` function so that we can capture the generated SQL and inspect it
            (let [orig       (var-get #'sql-jdbc.execute/execute-statement!)
                  sql-result (atom nil)]
              (with-redefs [sql-jdbc.execute/execute-statement!
                            (fn [driver stmt sql]
                              (reset! sql-result sql)
                              (orig driver stmt sql))]
                (mt/user-http-request
                 :rasta :post 200 "card"
                 (assoc (card-with-name-and-query card-name)
                        :dataset_query      (mt/native-query {:query "SELECT count(*) AS \"count\" FROM VENUES"})
                        :collection_id      (u/the-id collection))))
              (testing "check the correct metadata was fetched and was saved in the DB"
                (is (= [{:base_type     (count-base-type)
                         :effective_type (count-base-type)
                         :display_name  "count"
                         :name          "count"
                         :semantic_type :type/Quantity
                         :fingerprint   {:global {:distinct-count 1
                                                  :nil%           0.0},
                                         :type   {:type/Number {:min 100.0, :max 100.0, :avg 100.0, :q1 100.0, :q3 100.0 :sd nil}}}
                         :field_ref     [:field "count" {:base-type (count-base-type)}]}]
                       (db/select-one-field :result_metadata Card :name card-name))))
              (testing "Was the user id found in the generated SQL?"
                (is (= true
                       (boolean
                        (when-let [s @sql-result]
                          (re-find (re-pattern (str "userID: " (mt/user->id :rasta)))
                                   s)))))))))))))

(deftest create-card-with-collection-position
  (testing "Make sure we can create a Card with a Collection position"
    (mt/with-non-admin-groups-no-root-collection-perms
      (let [card-name (mt/random-name)]
        (mt/with-temp Collection [collection]
          (perms/grant-collection-readwrite-permissions! (perms-group/all-users) collection)
          (mt/with-model-cleanup [Card]
            (is (schema= {:collection_id       (s/eq (u/the-id collection))
                          :collection_position (s/eq 1)
                          :name                (s/eq card-name)
                          s/Keyword            s/Any}
                         (mt/user-http-request :rasta :post 200 "card"
                                               (assoc (card-with-name-and-query card-name)
                                                      :collection_id (u/the-id collection), :collection_position 1))))
            (is (schema= {:collection_id       (s/eq (u/the-id collection))
                          :collection_position (s/eq 1)
                          s/Keyword            s/Any}
                         (db/select-one Card :name card-name)))))))))

(deftest need-permission-for-collection
  (testing "You need to have Collection permissions to create a Card in a Collection"
    (mt/with-non-admin-groups-no-root-collection-perms
      (let [card-name (mt/random-name)]
        (mt/with-temp Collection [collection]
          (mt/with-model-cleanup [Card]
            (mt/user-http-request :rasta :post 403 "card"
                                  (assoc (card-with-name-and-query card-name)
                                         :collection_id (u/the-id collection)
                                         :collection_position 1))
            (is (nil? (some-> (db/select-one [Card :collection_id :collection_position] :name card-name)
                              (update :collection_id (partial = (u/the-id collection))))))))))))

(deftest create-card-check-adhoc-query-permissions-test
  (testing (str "Ad-hoc query perms should be required to save a Card -- otherwise people could save arbitrary "
                "queries, then run them.")
    ;; create a copy of the test data warehouse DB, then revoke permissions to it for All Users. Only admins should be
    ;; able to ad-hoc query it now.
    (mt/with-temp-copy-of-db
      (perms/revoke-data-perms! (perms-group/all-users) (mt/db))
      (let [query        (mt/mbql-query venues)
            create-card! (fn [test-user expected-status-code]
                           (mt/with-model-cleanup [Card]
                             (mt/user-http-request test-user :post expected-status-code "card"
                                                   (merge (mt/with-temp-defaults Card) {:dataset_query query}))))]
        (testing "admin should be able to save a Card if All Users doesn't have ad-hoc data perms"
          (is (some? (create-card! :crowberto 200))))
        (testing "non-admin should get an error"
          (testing "Permissions errors should be meaningful and include info for debugging (#14931)"
            (is (schema= {:message        (s/eq "You cannot save this Question because you do not have permissions to run its query.")
                          :query          (s/eq (mt/obj->json->obj query))
                          :required-perms [perms/Path]
                          :actual-perms   [perms/Path]
                          :trace          [s/Any]
                          s/Keyword       s/Any}
                         (create-card! :rasta 403)))))))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                    COPYING A CARD (POST /api/card/:id/copy)                                    |
;;; +----------------------------------------------------------------------------------------------------------------+

(deftest copy-card
  (testing "POST /api/card/:id/copy"
    (testing "Test that we can copy a Card"
      (mt/with-non-admin-groups-no-root-collection-perms
        (mt/with-temp Collection [collection]
          (perms/grant-collection-readwrite-permissions! (perms-group/all-users) collection)
          (mt/with-model-cleanup [Card]
            (let [card    (assoc (card-with-name-and-query (mt/random-name)
                                                           (mbql-count-query (mt/id) (mt/id :venues)))
                                 :collection_id (u/the-id collection))
                  card    (mt/user-http-request :rasta :post 200 "card" card)
                  newcard (mt/user-http-request :rasta :post 200 (format "card/%d/copy" (u/the-id card)))]
              (is (= (:name newcard) (str "Copy of " (:name card))))
              (is (= (:display newcard) (:display card)))
              (is (not= (:id newcard) (:id card))))))))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                            FETCHING A SPECIFIC CARD                                            |
;;; +----------------------------------------------------------------------------------------------------------------+

(deftest fetch-card-test
  (testing "GET /api/card/:id"
    (mt/with-non-admin-groups-no-root-collection-perms
      (mt/with-temp* [Collection [collection]
                      Card       [card {:collection_id (u/the-id collection)
                                        :dataset_query (mt/mbql-query venues)}]]
        (testing "You have to have Collection perms to fetch a Card"
          (is (= "You don't have permissions to do that."
                 (mt/user-http-request :rasta :get 403 (str "card/" (u/the-id card))))))

        (testing "Should be able to fetch the Card if you have Collection read perms"
          (perms/grant-collection-read-permissions! (perms-group/all-users) collection)
          (is (= (merge
                  card-defaults
                  (select-keys card [:id :name :entity_id :created_at :updated_at])
                  {:dashboard_count        0
                   :creator_id             (mt/user->id :rasta)
                   :creator                (merge
                                            (select-keys (mt/fetch-user :rasta) [:id :date_joined :last_login])
                                            {:common_name  "Rasta Toucan"
                                             :is_superuser false
                                             :is_qbnewb    true
                                             :last_name    "Toucan"
                                             :first_name   "Rasta"
                                             :email        "rasta@metabase.com"})
                   :dataset_query          (mt/obj->json->obj (:dataset_query card))
                   :display                "table"
                   :query_type             "query"
                   :visualization_settings {}
                   :can_write              false
                   :database_id            (mt/id) ; these should be inferred from the dataset_query
                   :table_id               (mt/id :venues)
                   :collection_id          (u/the-id collection)
                   :collection             (into {} collection)
                   :result_metadata        (mt/obj->json->obj (:result_metadata card))})
                 (mt/user-http-request :rasta :get 200 (str "card/" (u/the-id card))))))
        (testing "Card should include last edit info if available"
          (mt/with-temp* [User     [{user-id :id} {:first_name "Test" :last_name "User" :email "user@test.com"}]
                          Revision [_ {:model    "Card"
                                       :model_id (:id card)
                                       :user_id  user-id
                                       :object   (revision/serialize-instance card (:id card) card)}]]
            (is (= {:id true :email "user@test.com" :first_name "Test" :last_name "User" :timestamp true}
                   (-> (mt/user-http-request :rasta :get 200 (str "card/" (u/the-id card)))
                       mt/boolean-ids-and-timestamps
                       :last-edit-info)))))
        (testing "Card should include moderation reviews"
          (letfn [(clean [mr] (-> mr
                                  (update :user #(select-keys % [:id]))
                                  (select-keys [:status :text :user])))]
            (mt/with-temp* [ModerationReview [review {:moderated_item_id   (:id card)
                                                      :moderated_item_type "card"
                                                      :moderator_id        (mt/user->id :rasta)
                                                      :most_recent         true
                                                      :status              "verified"
                                                      :text                "lookin good"}]]
              (is (= [(clean (assoc review :user {:id true}))]
                     (->> (mt/user-http-request :rasta :get 200 (str "card/" (u/the-id card)))
                          mt/boolean-ids-and-timestamps
                          :moderation_reviews
                          (map clean)))))))))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                       UPDATING A CARD (PUT /api/card/:id)
;;; +----------------------------------------------------------------------------------------------------------------+


(deftest updating-a-card-that-doesnt-exist-should-give-a-404
  (is (= "Not found."
         (mt/user-http-request :crowberto :put 404 "card/12345"))))

(deftest test-that-we-can-edit-a-card
  (mt/with-temp Card [card {:name "Original Name"}]
    (with-cards-in-writeable-collection card
      (is (= "Original Name"
             (db/select-one-field :name Card, :id (u/the-id card))))
      (is (= {:timestamp true, :first_name "Rasta", :last_name "Toucan", :email "rasta@metabase.com", :id true}
             (-> (mt/user-http-request :rasta :put 200 (str "card/" (u/the-id card)) {:name "Updated Name"})
                 mt/boolean-ids-and-timestamps
                 :last-edit-info)))
      (is (= "Updated Name"
             (db/select-one-field :name Card, :id (u/the-id card)))))))

(deftest can-we-update-a-card-s-archived-status-
  (mt/with-temp Card [card]
    (with-cards-in-writeable-collection card
      (let [archived?     (fn [] (:archived (db/select-one Card :id (u/the-id card))))
            set-archived! (fn [archived]
                            (mt/user-http-request :rasta :put 200 (str "card/" (u/the-id card)) {:archived archived})
                            (archived?))]
        (is (= false
               (archived?)))
        (is (= true
               (set-archived! true)))
        (is (= false
               (set-archived! false)))))))

(deftest we-shouldn-t-be-able-to-archive-cards-if-we-don-t-have-collection--write--perms
  (mt/with-non-admin-groups-no-root-collection-perms
    (mt/with-temp* [Collection [collection]
                    Card       [card {:collection_id (u/the-id collection)}]]
      (perms/grant-collection-read-permissions! (perms-group/all-users) collection)
      (is (= "You don't have permissions to do that."
             (mt/user-http-request :rasta :put 403 (str "card/" (u/the-id card)) {:archived true}))))))

(deftest we-shouldn-t-be-able-to-unarchive-cards-if-we-don-t-have-collection--write--perms
  (mt/with-non-admin-groups-no-root-collection-perms
    (mt/with-temp* [Collection [collection]
                    Card       [card {:collection_id (u/the-id collection) :archived true}]]
      (perms/grant-collection-read-permissions! (perms-group/all-users) collection)
      (is (= "You don't have permissions to do that."
              (mt/user-http-request :rasta :put 403 (str "card/" (u/the-id card)) {:archived false}))))))

(deftest clear-description-test
  (testing "Can we clear the description of a Card? (#4738)"
    (mt/with-temp Card [card {:description "What a nice Card"}]
      (with-cards-in-writeable-collection card
        (mt/user-http-request :rasta :put 200 (str "card/" (u/the-id card)) {:description nil})
        (is (nil? (db/select-one-field :description Card :id (u/the-id card))))))))

(deftest description-should-be-blankable-as-well
  (mt/with-temp Card [card {:description "What a nice Card"}]
    (with-cards-in-writeable-collection card
      (mt/user-http-request :rasta :put 200 (str "card/" (u/the-id card)) {:description ""})
      (is (= ""
             (db/select-one-field :description Card :id (u/the-id card)))))))

(deftest update-card-parameters-test
  (testing "PUT /api/card/:id"
    (mt/with-temp Card [card]
      (testing "successfully update with valid parameters"
        (is (partial= {:parameters [{:id   "random-id"
                                     :type "number"}]}
                      (mt/user-http-request :rasta :put 200 (str "card/" (u/the-id card))
                                            {:parameters [{:id   "random-id"
                                                           :type "number"}]})))))

    (mt/with-temp Card [card {:parameters [{:id   "random-id"
                                            :type "number"}]}]
      (testing "nil parameters will no-op"
        (is (partial= {:parameters [{:id   "random-id"
                                     :type "number"}]}
                      (mt/user-http-request :rasta :put 200 (str "card/" (u/the-id card))
                                            {:parameters nil}))))
      (testing "an empty list will remove parameters"
        (is (partial= {:parameters []}
                      (mt/user-http-request :rasta :put 200 (str "card/" (u/the-id card))
                                            {:parameters []})))))))

(deftest update-card-parameter-mappings-test
  (testing "PUT /api/card/:id"
    (mt/with-temp Card [card]
      (testing "successfully update with valid parameter_mappings"
        (is (partial= {:parameter_mappings [{:parameter_id "abc123", :card_id 10,
                                             :target ["dimension" ["template-tags" "category"]]}]}
                      (mt/user-http-request :rasta :put 200 (str "card/" (u/the-id card))
                                            {:parameter_mappings [{:parameter_id "abc123", :card_id 10,
                                                                   :target ["dimension" ["template-tags" "category"]]}]})))))

    (mt/with-temp Card [card {:parameter_mappings [{:parameter_id "abc123", :card_id 10,
                                                    :target ["dimension" ["template-tags" "category"]]}]}]
      (testing "nil parameters will no-op"
        (is (partial= {:parameter_mappings [{:parameter_id "abc123", :card_id 10,
                                             :target ["dimension" ["template-tags" "category"]]}]}
                      (mt/user-http-request :rasta :put 200 (str "card/" (u/the-id card))
                                            {:parameters nil}))))
      (testing "an empty list will remove parameter_mappings"
        (is (partial= {:parameter_mappings []}
                      (mt/user-http-request :rasta :put 200 (str "card/" (u/the-id card))
                                            {:parameter_mappings []})))))))

(deftest update-embedding-params-test
  (testing "PUT /api/card/:id"
    (mt/with-temp Card [card]
      (testing "If embedding is disabled, even an admin should not be allowed to update embedding params"
        (mt/with-temporary-setting-values [enable-embedding false]
          (is (= "Embedding is not enabled."
                 (mt/user-http-request :crowberto :put 400 (str "card/" (u/the-id card))
                                       {:embedding_params {:abc "enabled"}})))))

      (mt/with-temporary-setting-values [enable-embedding true]
        (testing "Non-admin should not be allowed to update Card's embedding parms"
          (is (= "You don't have permissions to do that."
                 (mt/user-http-request :rasta :put 403 (str "card/" (u/the-id card))
                                       {:embedding_params {:abc "enabled"}}))))

        (testing "Admin should be able to update Card's embedding params"
          (mt/user-http-request :crowberto :put 200 (str "card/" (u/the-id card))
                                {:embedding_params {:abc "enabled"}})
          (is (= {:abc "enabled"}
                 (db/select-one-field :embedding_params Card :id (u/the-id card)))))))))

(deftest can-we-change-the-collection-position-of-a-card-
  (mt/with-temp Card [card]
    (with-cards-in-writeable-collection card
      (mt/user-http-request :rasta :put 200 (str "card/" (u/the-id card))
                            {:collection_position 1})
      (is (= 1
             (db/select-one-field :collection_position Card :id (u/the-id card)))))))

(deftest can-we-change-the-collection-preview-flag-of-a-card-
  (mt/with-temp Card [card]
    (with-cards-in-writeable-collection card
      (mt/user-http-request :rasta :put 200 (str "card/" (u/the-id card))
                            {:collection_preview false})
      (is (= false
             (db/select-one-field :collection_preview Card :id (u/the-id card)))))))

(deftest ---and-unset--unpin--it-as-well-
  (mt/with-temp Card [card {:collection_position 1}]
    (with-cards-in-writeable-collection card
      (mt/user-http-request :rasta :put 200 (str "card/" (u/the-id card))
                            {:collection_position nil})
      (is (= nil
             (db/select-one-field :collection_position Card :id (u/the-id card)))))))

(deftest ---we-shouldn-t-be-able-to-if-we-don-t-have-permissions-for-the-collection
  (mt/with-non-admin-groups-no-root-collection-perms
    (mt/with-temp* [Collection [collection]
                    Card       [card {:collection_id (u/the-id collection)}]]
      (mt/user-http-request :rasta :put 403 (str "card/" (u/the-id card))
                            {:collection_position 1})
      (is (= nil
             (db/select-one-field :collection_position Card :id (u/the-id card)))))))

(deftest gets-a-card
  (mt/with-non-admin-groups-no-root-collection-perms
    (mt/with-temp* [Collection [collection]
                    Card       [card {:collection_id (u/the-id collection), :collection_position 1}]]
      (mt/user-http-request :rasta :put 403 (str "card/" (u/the-id card))
                            {:collection_position nil})
      (is (= 1
             (db/select-one-field :collection_position Card :id (u/the-id card)))))))

(deftest update-card-validation-test
  (testing "PUT /api/card"
    (with-temp-native-card-with-params [_db card]
      (testing  "You cannot update a model to have variables"
        (is (= "A model made from a native SQL question cannot have a variable or field filter."
               (mt/user-http-request :rasta :put 400 (format "card/%d" (:id card)) {:dataset true})))))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                        Updating the positions of stuff                                         |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- name->position [results]
  (zipmap (map :name results)
          (map :collection_position results)))

(defn get-name->collection-position
  "Call the collection endpoint for `collection-id` as `user-kwd`. Will return a map with the names of the items as
  keys and their position as the value"
  [user-kwd collection-or-collection-id]
  (name->position (:data (mt/user-http-request user-kwd :get 200
                                               (format "collection/%s/items" (u/the-id collection-or-collection-id))))))

(defmacro with-ordered-items
  "Macro for creating many sequetial collection_position model instances, putting each in `collection`"
  {:style/indent :defn}
  [collection model-and-name-syms & body]
  `(mt/with-temp* ~(vec (mapcat (fn [idx [model-instance name-sym]]
                                  [model-instance [name-sym {:name                (name name-sym)
                                                             :collection_id       `(u/the-id ~collection)
                                                             :collection_position idx}]])
                                (iterate inc 1)
                                (partition-all 2 model-and-name-syms)))
     (testing (format "\nWith ordered items in Collection %d: %s"
                      (u/the-id ~collection)
                      ~(str/join ", " (for [[model symb] (partition-all 2 model-and-name-syms)]
                                        (format "%s %s" (name model) (name symb)))))
       ~@body)))

(deftest check-to-make-sure-we-can-move-a-card-in-a-collection-of-just-cards
  (mt/with-non-admin-groups-no-root-collection-perms
    (mt/with-temp Collection [collection]
      (with-ordered-items collection [Card a
                                      Card b
                                      Card c
                                      Card d]
        (perms/grant-collection-readwrite-permissions! (perms-group/all-users) collection)
        (mt/user-http-request :rasta :put 200 (str "card/" (u/the-id c))
                              {:collection_position 1})
        (is (= {"c" 1
                "a" 2
                "b" 3
                "d" 4}
               (get-name->collection-position :rasta collection)))))))

(deftest add-new-card-update-positions-test
  (testing "POST /api/card"
    (mt/with-non-admin-groups-no-root-collection-perms
      (mt/with-temp Collection [collection]
        (perms/grant-collection-readwrite-permissions! (perms-group/all-users) collection)
        (doseq [{:keys [message position expected]}
                [{:message  (str "Add a new card to an existing collection at position 1, will cause all existing "
                                 "positions to increment by 1")
                  :position 1
                  :expected {"d" 1
                             "a" 2
                             "b" 3
                             "c" 4}}
                 {:message  "Add new card in the middle, should only increment positions of objects that come after"
                  :position 2
                  :expected {"a" 1
                             "d" 2
                             "b" 3
                             "c" 4}}
                 {:message  "Add new card to end, shouldn't affect positions of other objects"
                  :position 4
                  :expected {"a" 1
                             "b" 2
                             "c" 3
                             "d" 4}}
                 {:message  (str "When adding a new Card to a Collection that does not have a position, it should not "
                                 "change positions of other objects")
                  :position nil
                  :expected {"a" 1
                             "b" 2
                             "c" 3
                             "d" nil}}]]
          (testing (str "\n" message)
            (with-ordered-items collection [Dashboard a
                                            Pulse     b
                                            Card      c]
              (testing "Original collection, before adding the new card"
                (is (= {"a" 1
                        "b" 2
                        "c" 3}
                       (get-name->collection-position :rasta collection))))
              (mt/with-model-cleanup [Card]
                (mt/user-http-request :rasta :post 200 "card"
                                      (merge (card-with-name-and-query "d")
                                             {:collection_id       (u/the-id collection)
                                              :collection_position position}))
                (is (= expected
                       (get-name->collection-position :rasta collection)))))))))))

(deftest move-existing-card-update-positions-test
  (testing "PUT /api/card/:id"
    (mt/with-non-admin-groups-no-root-collection-perms
      (mt/with-temp Collection [collection]
        (perms/grant-collection-readwrite-permissions! (perms-group/all-users) collection)
        (doseq [{:keys [message position expected]}
                [{:message  "Move existing Card to front"
                  :position 1
                  :expected {"d" 1
                             "a" 2
                             "b" 3
                             "c" 4
                             "e" 5
                             "f" 6}}
                 {:message  "Move existing Card to end"
                  :position 6
                  :expected {"a" 1
                             "b" 2
                             "c" 3
                             "e" 4
                             "f" 5
                             "d" 6}}
                 {:message  (str "When setting Collection position to nil, positions of other things should be adjusted "
                                 "accordingly")
                  :position nil
                  :expected {"a" 1
                             "b" 2
                             "c" 3
                             "e" 4
                             "f" 5
                             "d" nil}}]]
          (testing (str "\n" message)
            (with-ordered-items collection [Dashboard a
                                            Dashboard b
                                            Card      c
                                            Card      d
                                            Pulse     e
                                            Pulse     f]
              (testing "Original collection, before moving the Card"
                (is (= {"a" 1
                        "b" 2
                        "c" 3
                        "d" 4
                        "e" 5
                        "f" 6}
                       (get-name->collection-position :rasta collection))))
              (mt/user-http-request :rasta :put 200 (str "card/" (u/the-id d))
                                    {:collection_position position, :collection_id (u/the-id collection)})
              (is (= expected
                     (get-name->collection-position :rasta collection))))))))))

(deftest give-existing-card-a-position-test
  (testing "Give an existing Card without a `:collection_position` a position, and things should be adjusted accordingly"
    (mt/with-non-admin-groups-no-root-collection-perms
      (mt/with-temp* [Collection [{coll-id :id :as collection}]
                      Card       [_ {:name "a", :collection_id coll-id, :collection_position 1}]
                      ;; Card b does not start with a collection_position
                      Card       [b {:name "b", :collection_id coll-id}]
                      Dashboard  [_ {:name "c", :collection_id coll-id, :collection_position 2}]
                      Card       [_ {:name "d", :collection_id coll-id, :collection_position 3}]]
        (perms/grant-collection-readwrite-permissions! (perms-group/all-users) collection)
        (mt/user-http-request :rasta :put 200 (str "card/" (u/the-id b))
                              {:collection_position 2})
        (is (= {"a" 1
                "b" 2
                "c" 3
                "d" 4}
               (get-name->collection-position :rasta coll-id)))))))

(deftest change-collections-update-positions-test
  (testing (str "Change the Collection the Card is in, leave the position, should cause old and new collection to have "
                "their positions updated")
    (mt/with-non-admin-groups-no-root-collection-perms
      (mt/with-temp* [Collection [collection-1]
                      Collection [collection-2]]
        (with-ordered-items collection-1 [Dashboard a
                                          Card      b
                                          Pulse     c
                                          Dashboard d]
          (with-ordered-items collection-2 [Pulse     e
                                            Card      f
                                            Card      g
                                            Dashboard h]
            (perms/grant-collection-readwrite-permissions! (perms-group/all-users) collection-1)
            (perms/grant-collection-readwrite-permissions! (perms-group/all-users) collection-2)
            (mt/user-http-request :rasta :put 200 (str "card/" (u/the-id f))
                                  {:collection_id (u/the-id collection-1)})
            (testing "Dest collection should get updated positions"
              (is (= {"a" 1
                      "f" 2
                      "b" 3
                      "c" 4
                      "d" 5}
                     (get-name->collection-position :rasta collection-1))))
            (testing "Original collection should get updated positions"
              (is (= {"e" 1
                      "g" 2
                      "h" 3}
                     (get-name->collection-position :rasta collection-2))))))))))

(deftest change-both-collection-and-position-test
  (testing "Change the collection and the position, causing both collections and the updated card to have their order changed"
    (mt/with-non-admin-groups-no-root-collection-perms
      (mt/with-temp* [Collection [collection-1]
                      Collection [collection-2]]
        (with-ordered-items collection-1 [Pulse     a
                                          Pulse     b
                                          Dashboard c
                                          Dashboard d]
          (with-ordered-items collection-2 [Dashboard e
                                            Dashboard f
                                            Pulse     g
                                            Card      h]
            (perms/grant-collection-readwrite-permissions! (perms-group/all-users) collection-1)
            (perms/grant-collection-readwrite-permissions! (perms-group/all-users) collection-2)
            (mt/user-http-request :rasta :put 200 (str "card/" (u/the-id h))
                                  {:collection_position 1, :collection_id (u/the-id collection-1)})
            (is (= {"h" 1
                    "a" 2
                    "b" 3
                    "c" 4
                    "d" 5}
                   (get-name->collection-position :rasta collection-1)))
            (is (= {"e" 1
                    "f" 2
                    "g" 3}
                   (get-name->collection-position :rasta collection-2)))))))))

(deftest update-card-check-adhoc-query-permissions-test
  (testing (str "Ad-hoc query perms should be required to update the query for a Card -- otherwise people could save "
                "arbitrary queries, then run them.")
    ;; create a copy of the test data warehouse DB, then revoke permissions to it for All Users. Only admins should be
    ;; able to ad-hoc query it now.
    (mt/with-temp-copy-of-db
      (perms/revoke-data-perms! (perms-group/all-users) (mt/db))
      (mt/with-temp Card [{card-id :id} {:dataset_query (mt/mbql-query venues)}]
        (let [update-card! (fn [test-user expected-status-code request-body]
                             (mt/user-http-request test-user :put expected-status-code (format "card/%d" card-id)
                                                   request-body))]
          (testing "\nadmin"
            (testing "*should* be allowed to update query"
              (is (schema= {:id            (s/eq card-id)
                            :dataset_query (s/eq (mt/obj->json->obj (mt/mbql-query checkins)))
                            s/Keyword      s/Any}
                           (update-card! :crowberto 200 {:dataset_query (mt/mbql-query checkins)})))))

          (testing "\nnon-admin"
            (testing "should be allowed to update fields besides query"
              (is (schema= {:id       (s/eq card-id)
                            :name     (s/eq "Updated name")
                            s/Keyword s/Any}
                           (update-card! :rasta 200 {:name "Updated name"}))))

            (testing "should *not* be allowed to update query"
              (testing "Permissions errors should be meaningful and include info for debugging (#14931)"
                (is (schema= {:message        (s/eq "You cannot save this Question because you do not have permissions to run its query.")
                              :query          (s/eq (mt/obj->json->obj (mt/mbql-query users)))
                              :required-perms [perms/Path]
                              :actual-perms   [perms/Path]
                              :trace          [s/Any]
                              s/Keyword       s/Any}
                             (update-card! :rasta 403 {:dataset_query (mt/mbql-query users)}))))
              (testing "make sure query hasn't changed in the DB"
                (is (= (mt/mbql-query checkins)
                       (db/select-one-field :dataset_query Card :id card-id)))))

            (testing "should be allowed to update other fields if query is passed in but hasn't changed (##11719)"
              (is (schema= {:id            (s/eq card-id)
                            :name          (s/eq "Another new name")
                            :dataset_query (s/eq (mt/obj->json->obj (mt/mbql-query checkins)))
                            s/Keyword      s/Any}
                           (update-card! :rasta 200 {:name "Another new name", :dataset_query (mt/mbql-query checkins)}))))))))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                        Card updates that impact alerts                                         |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- rasta-alert-not-working [body-map]
  (mt/email-to :rasta {:subject "One of your alerts has stopped working"
                       :body    body-map}))

(defn- crowberto-alert-not-working [body-map]
  (mt/email-to :crowberto {:subject "One of your alerts has stopped working"
                           :body    body-map}))

(deftest alert-deletion-test
  (doseq [{:keys [message card deleted? expected-email f]}
          [{:message        "Archiving a Card should trigger Alert deletion"
            :deleted?       true
            :expected-email "the question was archived by Rasta Toucan"
            :f              (fn [{:keys [card]}]
                              (mt/user-http-request :rasta :put 200 (str "card/" (u/the-id card)) {:archived true}))}
           {:message        "Validate changing a display type triggers alert deletion"
            :card           {:display :table}
            :deleted?       true
            :expected-email "the question was edited by Rasta Toucan"
            :f              (fn [{:keys [card]}]
                              (mt/user-http-request :rasta :put 200 (str "card/" (u/the-id card)) {:display :line}))}
           {:message        "Changing the display type from line to table should force a delete"
            :card           {:display :line}
            :deleted?       true
            :expected-email "the question was edited by Rasta Toucan"
            :f              (fn [{:keys [card]}]
                              (mt/user-http-request :rasta :put 200 (str "card/" (u/the-id card)) {:display :table}))}
           {:message        "Removing the goal value will trigger the alert to be deleted"
            :card           {:display                :line
                             :visualization_settings {:graph.goal_value 10}}
            :deleted?       true
            :expected-email "the question was edited by Rasta Toucan"
            :f              (fn [{:keys [card]}]
                              (mt/user-http-request :rasta :put 200 (str "card/" (u/the-id card)) {:visualization_settings {:something "else"}}))}
           {:message        "Adding an additional breakout does not cause the alert to be removed if no goal is set"
            :card           {:display                :line
                             :visualization_settings {}
                             :dataset_query          (assoc-in
                                                      (mbql-count-query (mt/id) (mt/id :checkins))
                                                      [:query :breakout]
                                                      [[:field
                                                        (mt/id :checkins :date)
                                                        {:temporal-unit :hour}]])}
            :deleted?       false
            :f              (fn [{:keys [card]}]
                              (mt/user-http-request :crowberto :put 200 (str "card/" (u/the-id card))
                                                    {:dataset_query (assoc-in (mbql-count-query (mt/id) (mt/id :checkins))
                                                                              [:query :breakout] [[:field (mt/id :checkins :date) {:temporal-unit :hour}]
                                                                                                  [:field (mt/id :checkins :date) {:temporal-unit :minute}]])}))}
           {:message        "Adding an additional breakout will cause the alert to be removed if a goal is set"
            :card           {:display                :line
                             :visualization_settings {:graph.goal_value 10}
                             :dataset_query          (assoc-in
                                                      (mbql-count-query (mt/id) (mt/id :checkins))
                                                      [:query :breakout]
                                                      [[:field
                                                        (mt/id :checkins :date)
                                                        {:temporal-unit :hour}]])}
            :deleted?       true
            :expected-email "the question was edited by Crowberto Corv"
            :f              (fn [{:keys [card]}]
                              (mt/user-http-request :crowberto :put 200 (str "card/" (u/the-id card))
                                                    {:dataset_query (assoc-in (mbql-count-query (mt/id) (mt/id :checkins))
                                                                              [:query :breakout] [[:field (mt/id :checkins :date) {:temporal-unit :hour}]
                                                                                                  [:field (mt/id :checkins :date) {:temporal-unit :minute}]])}))}]]
    (testing message
      (mt/with-temp* [Card                  [card  card]
                      Pulse                 [pulse {:alert_condition  "rows"
                                                    :alert_first_only false
                                                    :creator_id       (mt/user->id :rasta)
                                                    :name             "Original Alert Name"}]

                      PulseCard             [_     {:pulse_id (u/the-id pulse)
                                                    :card_id  (u/the-id card)
                                                    :position 0}]
                      PulseChannel          [pc    {:pulse_id (u/the-id pulse)}]
                      PulseChannelRecipient [_     {:user_id          (mt/user->id :crowberto)
                                                    :pulse_channel_id (u/the-id pc)}]
                      PulseChannelRecipient [_     {:user_id          (mt/user->id :rasta)
                                                    :pulse_channel_id (u/the-id pc)}]]
        (with-cards-in-writeable-collection card
          (mt/with-fake-inbox
            (when deleted?
              (u/with-timeout 5000
                (mt/with-expected-messages 2
                  (f {:card card}))
               (is (= (merge (crowberto-alert-not-working {expected-email true})
                             (rasta-alert-not-working     {expected-email true}))
                      (mt/regex-email-bodies (re-pattern expected-email)))
                   (format "Email containing %s should have been sent to Crowberto and Rasta" (pr-str expected-email)))))
            (if deleted?
              (is (= nil (db/select-one Pulse :id (u/the-id pulse)))
                  "Alert should have been deleted")
              (is (not= nil (db/select-one Pulse :id (u/the-id pulse)))
                  "Alert should not have been deleted"))))))))

(deftest changing-the-display-type-from-line-to-area-bar-is-fine-and-doesnt-delete-the-alert
  (is (= {:emails-1 {}
          :pulse-1  true
          :emails-2 {}
          :pulse-2  true}
         (mt/with-temp* [Card                  [card  {:display                :line
                                                       :visualization_settings {:graph.goal_value 10}}]
                         Pulse                 [pulse {:alert_condition  "goal"
                                                       :alert_first_only false
                                                       :creator_id       (mt/user->id :rasta)
                                                       :name             "Original Alert Name"}]
                         PulseCard             [_     {:pulse_id (u/the-id pulse)
                                                       :card_id  (u/the-id card)
                                                       :position 0}]
                         PulseChannel          [pc    {:pulse_id (u/the-id pulse)}]
                         PulseChannelRecipient [_     {:user_id          (mt/user->id :rasta)
                                                       :pulse_channel_id (u/the-id pc)}]]
           (with-cards-in-writeable-collection card
             (mt/with-fake-inbox
               (array-map
                :emails-1 (do
                            (mt/user-http-request :rasta :put 200 (str "card/" (u/the-id card)) {:display :area})
                            (mt/regex-email-bodies #"the question was edited by Rasta Toucan"))
                :pulse-1  (boolean (db/select-one Pulse :id (u/the-id pulse)))
                :emails-2 (do
                            (mt/user-http-request :rasta :put 200 (str "card/" (u/the-id card)) {:display :bar})
                            (mt/regex-email-bodies #"the question was edited by Rasta Toucan"))
                :pulse-2  (boolean (db/select-one Pulse :id (u/the-id pulse))))))))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                          DELETING A CARD (DEPRECATED)                                          |
;;; +----------------------------------------------------------------------------------------------------------------+
;; Deprecated because you're not supposed to delete cards anymore. Archive them instead

(deftest check-that-we-can-delete-a-card
  (is (nil? (mt/with-temp Card [card]
              (with-cards-in-writeable-collection card
                (mt/user-http-request :rasta :delete 204 (str "card/" (u/the-id card)))
                (db/select-one Card :id (u/the-id card)))))))

;; deleting a card that doesn't exist should return a 404 (#1957)
(deftest deleting-a-card-that-doesnt-exist-should-return-a-404---1957-
  (is (= "Not found."
         (mt/user-http-request :crowberto :delete 404 "card/12345"))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                  Timelines                                                     |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- timelines-request
  [card include-events?]
  (if include-events?
    (mt/user-http-request :rasta :get 200 (str "card/" (u/the-id card) "/timelines") :include "events")
    (mt/user-http-request :rasta :get 200 (str "card/" (u/the-id card) "/timelines"))))

(defn- timelines-range-request
  [card {:keys [start end]}]
  (apply mt/user-http-request (concat [:rasta :get 200
                                       (str "card/" (u/the-id card) "/timelines")
                                       :include "events"]
                                      (when start [:start start])
                                      (when end [:end end]))))

(defn- timeline-names [timelines]
  (->> timelines (map :name) set))

(defn- event-names [timelines]
  (->> timelines (mapcat :events) (map :name) set))

(deftest timelines-test
  (testing "GET /api/card/:id/timelines"
    (mt/with-temp* [Collection [coll-a {:name "Collection A"}]
                    Collection [coll-b {:name "Collection B"}]
                    Collection [coll-c {:name "Collection C"}]
                    Card [card-a {:name          "Card A"
                                  :collection_id (u/the-id coll-a)}]
                    Card [card-b {:name          "Card B"
                                  :collection_id (u/the-id coll-b)}]
                    Card [card-c {:name          "Card C"
                                  :collection_id (u/the-id coll-c)}]
                    Timeline [tl-a {:name          "Timeline A"
                                    :collection_id (u/the-id coll-a)}]
                    Timeline [tl-b {:name          "Timeline B"
                                    :collection_id (u/the-id coll-b)}]
                    Timeline [_ {:name          "Timeline B-old"
                                 :collection_id (u/the-id coll-b)
                                 :archived      true}]
                    Timeline [_ {:name          "Timeline C"
                                 :collection_id (u/the-id coll-c)}]
                    TimelineEvent [_ {:name        "event-aa"
                                      :timeline_id (u/the-id tl-a)}]
                    TimelineEvent [_ {:name        "event-ab"
                                      :timeline_id (u/the-id tl-a)}]
                    TimelineEvent [_ {:name        "event-ba"
                                      :timeline_id (u/the-id tl-b)}]
                    TimelineEvent [_ {:name        "event-bb"
                                      :timeline_id (u/the-id tl-b)
                                      :archived    true}]]
      (testing "Timelines in the collection of the card are returned"
        (is (= #{"Timeline A"}
               (timeline-names (timelines-request card-a false)))))
      (testing "Timelines in the collection have a hydrated `:collection` key"
        (is (= #{(u/the-id coll-a)}
               (->> (timelines-request card-a false)
                    (map #(get-in % [:collection :id]))
                    set))))
      (testing "check that `:can_write` key is hydrated"
        (is (every?
             #(contains? % :can_write)
             (map :collection (timelines-request card-a false)))))
      (testing "Only un-archived timelines in the collection of the card are returned"
        (is (= #{"Timeline B"}
               (timeline-names (timelines-request card-b false)))))
      (testing "Timelines have events when `include=events` is passed"
        (is (= #{"event-aa" "event-ab"}
               (event-names (timelines-request card-a true)))))
      (testing "Timelines have only un-archived events when `include=events` is passed"
        (is (= #{"event-ba"}
               (event-names (timelines-request card-b true)))))
      (testing "Timelines with no events have an empty list on `:events` when `include=events` is passed"
        (is (= '()
               (->> (timelines-request card-c true) first :events)))))))

(deftest timelines-range-test
  (testing "GET /api/card/:id/timelines?include=events&start=TIME&end=TIME"
    (mt/with-temp* [Collection [collection {:name "Collection"}]
                    Card [card {:name          "Card A"
                                :collection_id (u/the-id collection)}]
                    Timeline [tl-a {:name          "Timeline A"
                                    :collection_id (u/the-id collection)}]
                    ;; the temp defaults set {:time_matters true}
                    TimelineEvent [_ {:name        "event-a"
                                      :timeline_id (u/the-id tl-a)
                                            :timestamp   #t "2020-01-01T10:00:00.0Z"}]
                    TimelineEvent [_ {:name        "event-b"
                                      :timeline_id (u/the-id tl-a)
                                            :timestamp   #t "2021-01-01T10:00:00.0Z"}]
                    TimelineEvent [_ {:name        "event-c"
                                      :timeline_id (u/the-id tl-a)
                                            :timestamp   #t "2022-01-01T10:00:00.0Z"}]
                    TimelineEvent [_ {:name        "event-d"
                                      :timeline_id (u/the-id tl-a)
                                            :timestamp   #t "2023-01-01T10:00:00.0Z"}]]
      (testing "Events are properly filtered when given only `start=` parameter"
        (is (= #{"event-c" "event-d"}
               (event-names (timelines-range-request card {:start "2022-01-01T10:00:00.0Z"})))))
      (testing "Events are properly filtered when given only `end=` parameter"
        (is (= #{"event-a" "event-b" "event-c"}
               (event-names (timelines-range-request card {:end "2022-01-01T10:00:00.0Z"})))))
      (testing "Events are properly filtered when given `start=` and `end=` parameters"
        (is (= #{"event-b" "event-c"}
               (event-names (timelines-range-request card {:start "2020-12-01T10:00:00.0Z"
                                                           :end   "2022-12-01T10:00:00.0Z"})))))
      (mt/with-temp TimelineEvent [_ {:name         "event-a2"
                                      :timeline_id  (u/the-id tl-a)
                                             :timestamp    #t "2020-01-01T10:00:00.0Z"
                                             :time_matters false}]
        (testing "Events are properly filtered considering the `time_matters` state."
          ;; notice that event-a and event-a2 have the same timestamp, but different time_matters states.
          ;; time_matters = false effectively means "We care only about the DATE of this event", so
          ;; if a start or end timestamp is on the same DATE (regardless of time), include the event
          (is (= #{"event-a2"}
                 (event-names (timelines-range-request card {:start "2020-01-01T11:00:00.0Z"
                                                             :end   "2020-12-01T10:00:00.0Z"})))))))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                            CSV/JSON/XLSX DOWNLOADS                                             |
;;; +----------------------------------------------------------------------------------------------------------------+

;;; Test GET /api/card/:id/query/csv & GET /api/card/:id/json & GET /api/card/:id/query/xlsx **WITH PARAMETERS**
(def ^:private ^:const ^String encoded-params
  (json/generate-string [{:type   :number
                          :target [:variable [:template-tag :category]]
                          :value  2}]))

(deftest csv-download-test
  (testing "no parameters"
    (with-temp-native-card [_ card]
      (with-cards-in-readable-collection card
        (is (= ["COUNT(*)"
                "75"]
               (str/split-lines
                (mt/user-http-request :rasta :post 200 (format "card/%d/query/csv"
                                                               (u/the-id card)))))))))
  (testing "with parameters"
    (with-temp-native-card-with-params [_ card]
      (with-cards-in-readable-collection card
        (is (= ["COUNT(*)"
                "8"]
               (str/split-lines
                (mt/user-http-request :rasta :post 200 (format "card/%d/query/csv?parameters=%s"
                                                               (u/the-id card) encoded-params)))))))))

(deftest json-download-test
  (testing "no parameters"
    (with-temp-native-card [_ card]
      (with-cards-in-readable-collection card
        (is (= [{(keyword "COUNT(*)") 75}]
               (mt/user-http-request :rasta :post 200 (format "card/%d/query/json" (u/the-id card))))))))
  (testing "with parameters"
    (with-temp-native-card-with-params [_ card]
      (with-cards-in-readable-collection card
        (is (= [{(keyword "COUNT(*)") 8}]
               (mt/user-http-request :rasta :post 200 (format "card/%d/query/json?parameters=%s"
                                                              (u/the-id card) encoded-params))))))))

(defn- parse-xlsx-results [results]
  (->> results
       ByteArrayInputStream.
       spreadsheet/load-workbook
       (spreadsheet/select-sheet "Query result")
       (spreadsheet/select-columns {:A :col})))

(deftest xlsx-download-test
  (testing "no parameters"
    (with-temp-native-card [_ card]
      (with-cards-in-readable-collection card
        (is (= [{:col "COUNT(*)"} {:col 75.0}]
               (parse-xlsx-results
                (mt/user-http-request :rasta :post 200 (format "card/%d/query/xlsx" (u/the-id card))
                                      {:request-options {:as :byte-array}})))))))
  (testing "with parameters"
    (with-temp-native-card-with-params [_ card]
      (with-cards-in-readable-collection card
        (is (= [{:col "COUNT(*)"} {:col 8.0}]
               (parse-xlsx-results
                (mt/user-http-request :rasta :post 200 (format "card/%d/query/xlsx?parameters=%s"
                                                               (u/the-id card) encoded-params)
                                      {:request-options {:as :byte-array}}))))))))

(deftest download-default-constraints-test
  (mt/with-temp Card [card {:dataset_query {:database   (mt/id)
                                            :type       :query
                                            :query      {:source-table (mt/id :venues)}
                                            :middleware {:add-default-userland-constraints? true
                                                         :userland-query?                   true}}}]
    (with-cards-in-readable-collection card
      (let [orig qp.card/run-query-for-card-async]
        (with-redefs [qp.card/run-query-for-card-async (fn [card-id export-format & options]
                                                         (apply orig card-id export-format
                                                                :run (fn [{:keys [constraints]} _]
                                                                       {:constraints constraints})
                                                                options))]
          (testing "Sanity check: this CSV download should not be subject to C O N S T R A I N T S"
            (is (= {:constraints nil}
                   (mt/user-http-request :rasta :post 200 (format "card/%d/query/csv" (u/the-id card))))))
          (with-redefs [qp.constraints/default-query-constraints (constantly {:max-results 10, :max-results-bare-rows 10})]
            (testing (str "Downloading CSV/JSON/XLSX results shouldn't be subject to the default query constraints -- even "
                          "if the query comes in with `add-default-userland-constraints` (as will be the case if the query "
                          "gets saved from one that had it -- see #9831)")
              (is (= {:constraints nil}
                     (mt/user-http-request :rasta :post 200 (format "card/%d/query/csv" (u/the-id card))))))

            (testing (str "non-\"download\" queries should still get the default constraints (this also is a sanitiy "
                          "check to make sure the `with-redefs` in the test above actually works)")
              (is (= {:constraints {:max-results 10, :max-results-bare-rows 10}}
                     (mt/user-http-request :rasta :post 200 (format "card/%d/query" (u/the-id card))))))))))))

(defn- test-download-response-headers
  [url]
  (-> (client/client-full-response (test.users/username->token :rasta)
                                   :post 200 url
                                   :query (json/generate-string (mt/mbql-query checkins {:limit 1})))
      :headers
      (select-keys ["Cache-Control" "Content-Disposition" "Content-Type" "Expires" "X-Accel-Buffering"])
      (update "Content-Disposition" #(some-> % (str/replace #"my_awesome_card_.+(\.\w+)"
                                                            "my_awesome_card_<timestamp>$1")))))

(deftest download-response-headers-test
  (testing "Make sure CSV/etc. download requests come back with the correct headers"
    (mt/with-temp Card [card {:name "My Awesome Card"}]
      (is (= {"Cache-Control"       "max-age=0, no-cache, must-revalidate, proxy-revalidate"
              "Content-Disposition" "attachment; filename=\"my_awesome_card_<timestamp>.csv\""
              "Content-Type"        "text/csv"
              "Expires"             "Tue, 03 Jul 2001 06:00:00 GMT"
              "X-Accel-Buffering"   "no"}
             (test-download-response-headers (format "card/%d/query/csv" (u/the-id card)))))
      (is (= {"Cache-Control"       "max-age=0, no-cache, must-revalidate, proxy-revalidate"
              "Content-Disposition" "attachment; filename=\"my_awesome_card_<timestamp>.json\""
              "Content-Type"        "application/json;charset=utf-8"
              "Expires"             "Tue, 03 Jul 2001 06:00:00 GMT"
              "X-Accel-Buffering"   "no"}
             (test-download-response-headers (format "card/%d/query/json" (u/the-id card)))))
      (is (= {"Cache-Control"       "max-age=0, no-cache, must-revalidate, proxy-revalidate"
              "Content-Disposition" "attachment; filename=\"my_awesome_card_<timestamp>.xlsx\""
              "Content-Type"        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
              "Expires"             "Tue, 03 Jul 2001 06:00:00 GMT"
              "X-Accel-Buffering"   "no"}
             (test-download-response-headers (format "card/%d/query/xlsx" (u/the-id card))))))))


;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                                  COLLECTIONS                                                   |
;;; +----------------------------------------------------------------------------------------------------------------+

(deftest make-sure-we-can-create-a-card-and-specify-its--collection-id--at-the-same-time
  (mt/with-non-admin-groups-no-root-collection-perms
    (mt/with-temp Collection [collection]
      (perms/grant-collection-readwrite-permissions! (perms-group/all-users) collection)
      (mt/with-model-cleanup [Card]
        (let [card (mt/user-http-request :rasta :post 200 "card"
                                         (assoc (card-with-name-and-query)
                                                :collection_id (u/the-id collection)))]
          (is (= (db/select-one-field :collection_id Card :id (u/the-id card))
                 (u/the-id collection))))))))

(deftest make-sure-we-card-creation-fails-if-we-try-to-set-a--collection-id--we-don-t-have-permissions-for
  (testing "POST /api/card"
    (testing "You must have permissions for the parent Collection to create a new Card in it"
      (mt/with-non-admin-groups-no-root-collection-perms
        (mt/with-temp Collection [collection]
          (mt/with-model-cleanup [Card]
            (is (schema= {:message (s/eq "You do not have curate permissions for this Collection.")
                          s/Keyword s/Any}
                         (mt/user-http-request :rasta :post 403 "card"
                                               (assoc (card-with-name-and-query) :collection_id (u/the-id collection)))))))))))

(deftest set-card-collection-id-test
  (testing "Should be able to set the Collection ID of a Card in the Root Collection (i.e., `collection_id` is nil)"
    (mt/with-temp* [Card       [card]
                    Collection [collection]]
      (mt/user-http-request :crowberto :put 200 (str "card/" (u/the-id card)) {:collection_id (u/the-id collection)})
      (is (= (db/select-one-field :collection_id Card :id (u/the-id card))
             (u/the-id collection))))))

(deftest update-card-require-parent-perms-test
  (testing "Should require perms for the parent collection to change a Card's properties"
    (mt/with-non-admin-groups-no-root-collection-perms
      (mt/with-temp* [Collection [collection]
                      Card       [card       {:collection_id (u/the-id collection)}]]
        (is (= "You don't have permissions to do that."
               (mt/user-http-request :rasta :put 403 (str "card/" (u/the-id card))
                                     {:name "Number of Blueberries Consumed Per Month"})))))))

(deftest change-collection-permissions-test
  (testing "PUT /api/card/:id"
    (testing "\nChange the `collection_id` of a Card"
      (mt/with-non-admin-groups-no-root-collection-perms
        (mt/with-temp* [Collection [original-collection]
                        Collection [new-collection]
                        Card       [card                {:collection_id (u/the-id original-collection)}]]
          (letfn [(change-collection! [expected-status-code]
                    (mt/user-http-request :rasta :put expected-status-code (str "card/" (u/the-id card))
                                          {:collection_id (u/the-id new-collection)}))]
            (testing "requires write permissions for the new Collection"
              (is (= "You don't have permissions to do that."
                     (change-collection! 403))))

            (testing "requires write permissions for the current Collection"
              (perms/grant-collection-readwrite-permissions! (perms-group/all-users) new-collection)
              (is (= "You don't have permissions to do that."
                     (change-collection! 403))))

            (testing "Should be able to change it once you have perms for both collections"
              (perms/grant-collection-readwrite-permissions! (perms-group/all-users) original-collection)
              (change-collection! 200)
              (is (= (db/select-one-field :collection_id Card :id (u/the-id card))
                     (u/the-id new-collection))))))))))


;;; ------------------------------ Bulk Collections Update (POST /api/card/collections) ------------------------------

(defn- collection-names
  "Given a sequences of `cards-or-card-ids`, return a corresponding sequence of names of the Collection each Card is
  in."
  [cards-or-card-ids]
  (when (seq cards-or-card-ids)
    (let [cards               (db/select [Card :collection_id] :id [:in (map u/the-id cards-or-card-ids)])
          collection-ids      (set (filter identity (map :collection_id cards)))
          collection-id->name (when (seq collection-ids)
                                (db/select-id->field :name Collection :id [:in collection-ids]))]
      (for [card cards]
        (get collection-id->name (:collection_id card))))))

(defn- POST-card-collections!
  "Update the Collection of `cards-or-card-ids` via the `POST /api/card/collections` endpoint using `username`; return
  the response of this API request and the latest Collection IDs from the database."
  [username expected-status-code collection-or-collection-id-or-nil cards-or-card-ids]
  (array-map
   :response
   (mt/user-http-request username :post expected-status-code "card/collections"
                         {:collection_id (when collection-or-collection-id-or-nil
                                           (u/the-id collection-or-collection-id-or-nil))
                          :card_ids      (map u/the-id cards-or-card-ids)})

   :collections
   (collection-names cards-or-card-ids)))

(deftest changed?-test
  (letfn [(changed? [before after]
            (#'api.card/changed? api.card/card-compare-keys before after))]
   (testing "Ignores keyword/string"
     (is (false? (changed? {:dataset_query {:type :query}} {:dataset_query {:type "query"}}))))
   (testing "Ignores properties not in `api.card/card-compare-keys"
     (is (false? (changed? {:collection_id 1
                            :collection_position 0}
                           {:collection_id 2
                            :collection_position 1}))))
   (testing "Sees changes"
     (is (true? (changed? {:dataset_query {:type :query}}
                          {:dataset_query {:type :query
                                           :query {}}})))
     (testing "But only when they are different in the after, not just omitted"
       (is (false? (changed? {:dataset_query {} :collection_id 1}
                             {:collection_id 1})))
       (is (true? (changed? {:dataset_query {} :collection_id 1}
                            {:dataset_query nil :collection_id 1})))))))

(deftest update-verified-card-test
  (tools.macro/macrolet
      [(with-card [verified & body]
         `(mt/with-temp* ~(cond-> `[Collection [~'collection]
                                    Collection [~'collection2]
                                    Card       [~'card {:collection_id (u/the-id ~'collection)
                                                        :dataset_query (mt/mbql-query ~'venues)}]]
                            (= verified :verified)
                            (into
                             `[ModerationReview
                               [~'review {:moderated_item_id   (:id ~'card)
                                          :moderated_item_type "card"
                                          :moderator_id        (mt/user->id :rasta)
                                          :most_recent         true
                                          :status              "verified"
                                          :text                "lookin good"}]]))
            ~@body))]
      (letfn [(verified? [card]
                (-> card (hydrate [:moderation_reviews :moderator_details])
                    :moderation_reviews first :status #{"verified"} boolean))
              (reviews [card]
                (db/select ModerationReview
                           :moderated_item_type "card"
                           :moderated_item_id (u/the-id card)
                           {:order-by [[:id :desc]]}))
              (update-card [card diff]
                (mt/user-http-request :rasta :put 200 (str "card/" (u/the-id card)) (merge card diff)))]
        (testing "Changing core attributes un-verifies the card"
          (with-card :verified
            (is (verified? card))
            (update-card card (update-in card [:dataset_query :query :source-table] inc))
            (is (not (verified? card)))
            (testing "The unverification edit has explanatory text"
              (is (= "Unverified due to edit"
                     (-> (reviews card) first :text))))))
        (testing "Changing some attributes does not unverify"
          (tools.macro/macrolet [(remains-verified [& body]
                                   `(~'with-card :verified
                                     (is (~'verified? ~'card) "Not verified initially")
                                     ~@body
                                     (is (~'verified? ~'card) "Not verified after action")))]
            (testing "changing collection"
              (remains-verified
               (update-card card {:collection_id (u/the-id collection2)})))
            (testing "pinning"
              (remains-verified
               (update-card card {:collection_position 1})))
            (testing "making public"
              (remains-verified
               (update-card card {:made_public_by_id (mt/user->id :rasta)
                                  :public_uuid (UUID/randomUUID)})))
            (testing "Changing description"
              (remains-verified
               (update-card card {:description "foo"})))
            (testing "Changing name"
              (remains-verified
               (update-card card {:name "foo"})))
            (testing "Changing archived"
              (remains-verified
               (update-card card {:archived true})))
            (testing "Changing display"
              (remains-verified
               (update-card card {:display :line})))
            (testing "Changing visualization settings"
              (remains-verified
               (update-card card {:visualization_settings {:table.cell_column "FOO"}})))))
        (testing "Does not add a new nil moderation review when not verified"
          (with-card :not-verified
            (is (empty? (reviews card)))
            (update-card card {:description "a new description"})
            (is (empty? (reviews card)))))
        (testing "Does not add nil moderation reviews when there are reviews but not verified"
          ;; testing that we aren't just adding a nil moderation each time we update a card
          (with-card :verified
            (is (verified? card))
            (moderation-review/create-review! {:moderated_item_id   (u/the-id card)
                                               :moderated_item_type "card"
                                               :moderator_id        (mt/user->id :rasta)
                                               :status              nil})
            (is (not (verified? card)))
            (is (= 2 (count (reviews card))))
            (update-card card {:description "a new description"})
            (is (= 2 (count (reviews card)))))))))

(deftest test-that-we-can-bulk-move-some-cards-with-no-collection-into-a-collection
  (mt/with-temp* [Collection [collection {:name "Pog Collection"}]
                  Card       [card-1]
                  Card       [card-2]]
    (is (= {:response    {:status "ok"}
            :collections ["Pog Collection"
                          "Pog Collection"]}
           (POST-card-collections! :crowberto 200 collection [card-1 card-2])))))

(deftest test-that-we-can-bulk-move-some-cards-from-one-collection-to-another
  (mt/with-temp* [Collection [old-collection {:name "Old Collection"}]
                  Collection [new-collection {:name "New Collection"}]
                  Card       [card-1         {:collection_id (u/the-id old-collection)}]
                  Card       [card-2         {:collection_id (u/the-id old-collection)}]]
    (is (= {:response    {:status "ok"}
            :collections ["New Collection" "New Collection"]}
           (POST-card-collections! :crowberto 200 new-collection [card-1 card-2])))))

(deftest test-that-we-can-bulk-remove-some-cards-from-a-collection
  (mt/with-temp* [Collection [collection]
                  Card       [card-1     {:collection_id (u/the-id collection)}]
                  Card       [card-2     {:collection_id (u/the-id collection)}]]
    (is (= {:response    {:status "ok"}
            :collections [nil nil]}
           (POST-card-collections! :crowberto 200 nil [card-1 card-2])))))

(deftest check-that-we-aren-t-allowed-to-move-cards-if-we-don-t-have-permissions-for-destination-collection
  (mt/with-non-admin-groups-no-root-collection-perms
    (mt/with-temp* [Collection [collection]
                    Card       [card-1]
                    Card       [card-2]]
      (is (= {:response    "You don't have permissions to do that."
              :collections [nil nil]}
             (POST-card-collections! :rasta 403 collection [card-1 card-2]))))))

(deftest check-that-we-aren-t-allowed-to-move-cards-if-we-don-t-have-permissions-for-source-collection
  (mt/with-non-admin-groups-no-root-collection-perms
    (mt/with-temp* [Collection [collection {:name "Horseshoe Collection"}]
                    Card       [card-1     {:collection_id (u/the-id collection)}]
                    Card       [card-2     {:collection_id (u/the-id collection)}]]
      (is (= {:response    "You don't have permissions to do that."
              :collections ["Horseshoe Collection" "Horseshoe Collection"]}
             (POST-card-collections! :rasta 403 nil [card-1 card-2]))))))

(deftest check-that-we-aren-t-allowed-to-move-cards-if-we-don-t-have-permissions-for-the-card
  (mt/with-non-admin-groups-no-root-collection-perms
    (mt/with-temp* [Collection [collection]
                    Database   [database]
                    Table      [table      {:db_id (u/the-id database)}]
                    Card       [card-1     {:dataset_query (mbql-count-query (u/the-id database) (u/the-id table))}]
                    Card       [card-2     {:dataset_query (mbql-count-query (u/the-id database) (u/the-id table))}]]
      (perms/revoke-data-perms! (perms-group/all-users) (u/the-id database))
      (perms/grant-collection-readwrite-permissions! (perms-group/all-users) collection)
      (is (= {:response    "You don't have permissions to do that."
              :collections [nil nil]}
             (POST-card-collections! :rasta 403 collection [card-1 card-2]))))))

;; Test that we can bulk move some Cards from one collection to another, while updating the collection position of the
;; old collection and the new collection
(deftest bulk-move-cards
  (mt/with-temp* [Collection [{coll-id-1 :id}      {:name "Old Collection"}]
                  Collection [{coll-id-2 :id
                               :as new-collection} {:name "New Collection"}]
                  Card       [card-a               {:name "a", :collection_id coll-id-1, :collection_position 1}]
                  Card       [card-b               {:name "b", :collection_id coll-id-1, :collection_position 2}]
                  Card       [_                    {:name "c", :collection_id coll-id-1, :collection_position 3}]
                  Card       [_                    {:name "d", :collection_id coll-id-2, :collection_position 1}]
                  Card       [_                    {:name "e", :collection_id coll-id-2, :collection_position 2}]
                  Card       [_                    {:name "f", :collection_id coll-id-2, :collection_position 3}]]
    (is (= {:response    {:status "ok"}
            :collections ["New Collection" "New Collection"]}
           (POST-card-collections! :crowberto 200 new-collection [card-a card-b])))
    (is (= {"a" 4                       ;-> Moved to the new collection, gets the first slot available
            "b" 5
            "c" 1                       ;-> With a and b no longer in the collection, c is first
            "d" 1                       ;-> Existing cards in new collection are untouched and position unchanged
            "e" 2
            "f" 3}
           (merge (name->position (:data (mt/user-http-request :crowberto :get 200 (format "collection/%s/items" coll-id-1)
                                                               :model "card" :archived "false")))
                  (name->position (:data (mt/user-http-request :crowberto :get 200 (format "collection/%s/items" coll-id-2)
                                                               :model "card" :archived "false"))))))))

(deftest moving-a-card-without-a-collection-position-keeps-the-collection-position-nil
  (mt/with-temp* [Collection [{coll-id-1 :id}      {:name "Old Collection"}]
                  Collection [{coll-id-2 :id
                               :as new-collection} {:name "New Collection"}]
                  Card       [card-a               {:name "a", :collection_id coll-id-1}]
                  Card       [card-b               {:name "b", :collection_id coll-id-2, :collection_position 1}]
                  Card       [_card-c              {:name "c", :collection_id coll-id-2, :collection_position 2}]]
    (is (= {:response    {:status "ok"}
            :collections ["New Collection" "New Collection"]}
           (POST-card-collections! :crowberto 200 new-collection [card-a card-b])))
    (is (= {"a" nil
            "b" 1
            "c" 2}
           (merge (name->position (:data (mt/user-http-request :crowberto :get 200 (format "collection/%s/items" coll-id-1)
                                                               :model "card" :archived "false")))
                  (name->position (:data (mt/user-http-request :crowberto :get 200 (format "collection/%s/items" coll-id-2)
                                                               :model "card" :archived "false"))))))))

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                            PUBLIC SHARING ENDPOINTS                                            |
;;; +----------------------------------------------------------------------------------------------------------------+

(defn- shared-card []
  {:public_uuid       (str (UUID/randomUUID))
   :made_public_by_id (mt/user->id :crowberto)})

(deftest share-card-test
  (testing "POST /api/card/:id/public_link"
    (mt/with-temporary-setting-values [enable-public-sharing true]
      (mt/with-temp Card [card]
        (let [{uuid :uuid} (mt/user-http-request :crowberto :post 200 (format "card/%d/public_link" (u/the-id card)))]
          (is (= true
                 (boolean (db/exists? Card :id (u/the-id card), :public_uuid uuid)))))))))

(deftest share-card-preconditions-test
  (testing "POST /api/card/:id/public_link"
    (testing "Public sharing has to be enabled to share a Card"
      (mt/with-temporary-setting-values [enable-public-sharing false]
        (mt/with-temp Card [card]
          (is (= "Public sharing is not enabled."
                 (mt/user-http-request :crowberto :post 400 (format "card/%d/public_link" (u/the-id card))))))))

    (mt/with-temporary-setting-values [enable-public-sharing true]
      (testing "Have to be an admin to share a Card"
        (mt/with-temp Card [card]
          (is (= "You don't have permissions to do that."
                 (mt/user-http-request :rasta :post 403 (format "card/%d/public_link" (u/the-id card)))))))

      (testing "Cannot share an archived Card"
        (mt/with-temp Card [card {:archived true}]
          (is (schema= {:message    (s/eq "The object has been archived.")
                        :error_code (s/eq "archived")
                        s/Keyword   s/Any}
                       (mt/user-http-request :crowberto :post 404 (format "card/%d/public_link" (u/the-id card)))))))

      (testing "Cannot share a Card that doesn't exist"
        (is (= "Not found."
               (mt/user-http-request :crowberto :post 404 (format "card/%d/public_link" Integer/MAX_VALUE))))))))

(deftest share-already-shared-card-test
  (testing "POST /api/card/:id/public_link"
    (testing "Attempting to share a Card that's already shared should return the existing public UUID"
      (mt/with-temporary-setting-values [enable-public-sharing true]
        (mt/with-temp Card [card (shared-card)]
          (is (= (:public_uuid card)
                 (:uuid (mt/user-http-request :crowberto :post 200 (format
                                                                    "card/%d/public_link"
                                                                    (u/the-id card)))))))))))

(deftest unshare-card-test
  (testing "DELETE /api/card/:id/public_link"
    (mt/with-temporary-setting-values [enable-public-sharing true]
      (mt/with-temp Card [card (shared-card)]
        (testing "requires superuser"
          (is (= "You don't have permissions to do that."
                 (mt/user-http-request :rasta :delete 403 (format "card/%d/public_link" (u/the-id card))))))

        (mt/user-http-request :crowberto :delete 204 (format "card/%d/public_link" (u/the-id card)))
        (is (= false
               (db/exists? Card :id (u/the-id card), :public_uuid (:public_uuid card))))))))

(deftest unshare-card-preconditions-test
  (testing "DELETE /api/card/:id/public_link\n"
    (mt/with-temporary-setting-values [enable-public-sharing true]
      (testing "Endpoint should return 404 if Card isn't shared"
        (mt/with-temp Card [card]
          (is (= "Not found."
                 (mt/user-http-request :crowberto :delete 404 (format "card/%d/public_link" (u/the-id card)))))))

      (testing "You have to be an admin to unshare a Card"
        (mt/with-temp Card [card (shared-card)]
          (is (= "You don't have permissions to do that."
                 (mt/user-http-request :rasta :delete 403 (format "card/%d/public_link" (u/the-id card)))))))

      (testing "Endpoint should 404 if Card doesn't exist"
        (is (= "Not found."
               (mt/user-http-request :crowberto :delete 404 (format "card/%d/public_link" Integer/MAX_VALUE))))))))

(deftest test-that-we-can-fetch-a-list-of-publicly-accessible-cards
  (testing "GET /api/card/public"
    (mt/with-temporary-setting-values [enable-public-sharing true]
      (mt/with-temp Card [_ (shared-card)]
        (testing "Test that it requires superuser"
          (is (= "You don't have permissions to do that."
                 (mt/user-http-request :rasta :get 403 "card/public"))))

        (testing "Test that superusers can fetch a list of publicly-accessible cards"
          (is (= [{:name true, :id true, :public_uuid true}]
                 (for [card (mt/user-http-request :crowberto :get 200 "card/public")]
                   (m/map-vals boolean (select-keys card [:name :id :public_uuid]))))))))))

(deftest test-that-we-can-fetch-a-list-of-embeddable-cards
  (testing "GET /api/card/embeddable"
    (mt/with-temporary-setting-values [enable-embedding true]
      (mt/with-temp Card [_ {:enable_embedding true}]
        (is (= [{:name true, :id true}]
               (for [card (mt/user-http-request :crowberto :get 200 "card/embeddable")]
                 (m/map-vals boolean (select-keys card [:name :id])))))))))

(deftest test-related-recommended-entities
  (mt/with-temp Card [card]
    (is (schema= {:table             s/Any
                  :metrics           s/Any
                  :segments          s/Any
                  :dashboard-mates   s/Any
                  :similar-questions s/Any
                  :canonical-metric  s/Any
                  :dashboards        s/Any
                  :collections       s/Any}
                 (mt/user-http-request :crowberto :get 200 (format "card/%s/related" (u/the-id card)))))))

(deftest pivot-card-test
  (mt/test-drivers (api.pivots/applicable-drivers)
    (mt/dataset sample-dataset
      (testing "POST /api/card/pivot/:card-id/query"
        (mt/with-temp Card [card (api.pivots/pivot-card)]
          (let [result (mt/user-http-request :rasta :post 202 (format "card/pivot/%d/query" (u/the-id card)))
                rows   (mt/rows result)]
            (is (= 1144 (:row_count result)))
            (is (= "completed" (:status result)))
            (is (= 6 (count (get-in result [:data :cols]))))
            (is (= 1144 (count rows)))

            (is (= ["AK" "Affiliate" "Doohickey" 0 18 81] (first rows)))
            (is (= ["MS" "Organic" "Gizmo" 0 16 42] (nth rows 445)))
            (is (= [nil nil nil 7 18760 69540] (last rows)))))))))

(deftest dataset-card
  (testing "Setting a question to a dataset makes it viz type table"
    (mt/with-temp Card [card {:display       :bar
                              :dataset_query (mbql-count-query)}]
      (is (= {:display "table" :dataset true}
             (-> (mt/user-http-request :crowberto :put 200 (str "card/" (u/the-id card))
                                       (assoc card :dataset true))
                 (select-keys [:display :dataset]))))))
  (testing "Cards preserve their edited metadata"
    (letfn [(query! [card-id] (mt/user-http-request :rasta :post 202 (format "card/%d/query" card-id)))
            (only-user-edits [col] (select-keys col [:name :description :display_name :semantic_type]))
            (refine-type [base-type] (condp #(isa? %2 %1) base-type
                                       :type/Integer :type/Quantity
                                       :type/Float :type/Cost
                                       :type/Text :type/Name
                                       base-type))
            (add-preserved [cols] (map merge
                                       cols
                                       (repeat {:description "user description"
                                                :display_name "user display name"})
                                       (map (comp
                                             (fn [x] {:semantic_type x})
                                             refine-type
                                             :base_type)
                                            cols)))]
      (mt/with-temp* [Card [mbql-ds {:dataset_query
                                     {:database (mt/id)
                                      :type     :query
                                      :query    {:source-table (mt/id :venues)}}
                                     :dataset true}]
                      Card [mbql-nested {:dataset_query
                                         {:database (mt/id)
                                          :type     :query
                                          :query    {:source-table
                                                     (str "card__" (u/the-id mbql-ds))}}}]
                      Card [native-ds {:dataset true
                                       :dataset_query
                                       {:database (mt/id)
                                        :type :native
                                        :native
                                        {:query
                                         "select * from venues"
                                         :template-tags {}}}}]
                      Card [native-nested {:dataset_query
                                           {:database (mt/id)
                                            :type :query
                                            :query {:source-table
                                                    (str "card__" (u/the-id native-ds))}}}]]
        (doseq [[_query-type card-id nested-id] [[:mbql
                                                  (u/the-id mbql-ds) (u/the-id mbql-nested)]
                                                 [:native
                                                  (u/the-id native-ds) (u/the-id native-nested)]]]
          (query! card-id) ;; populate metadata
          (let [metadata (db/select-one-field :result_metadata Card :id card-id)
                ;; simulate updating metadat with user changed stuff
                user-edited (add-preserved metadata)]
            (db/update! Card card-id :result_metadata user-edited)
            (testing "Saved metadata preserves user edits"
              (is (= (map only-user-edits user-edited)
                     (map only-user-edits (db/select-one-field :result_metadata Card :id card-id)))))
            (testing "API response includes user edits"
              (is (= (map only-user-edits user-edited)
                     (->> (query! card-id)
                          :data :results_metadata :columns
                          (map only-user-edits)
                          (map #(update % :semantic_type keyword))))))
            (testing "Nested queries have metadata"
              (is (= (map only-user-edits user-edited)
                     (->> (query! nested-id)
                          :data :results_metadata :columns
                          (map only-user-edits)
                          (map #(update % :semantic_type keyword)))))))))))
  (testing "Cards preserve edits to metadata when query changes"
    (let [query          (mt/mbql-query venues {:fields [$id $name]})
          modified-query (mt/mbql-query venues {:fields [$id $name $price]})
          norm           (comp str/upper-case :name)
          to-native      (fn [q]
                           {:database (:database q)
                            :type     :native
                            :native   (mt/compile q)})
          update-card!  (fn [card]
                          (mt/user-http-request :rasta :put 200
                                                (str "card/" (u/the-id card)) card))]
      (doseq [[query-type query modified-query] [["mbql"   query modified-query]
                                                 ["native" (to-native query) (to-native modified-query)]]]
        (testing (str "For: " query-type)
          (mt/with-model-cleanup [Card]
            (let [{metadata :result_metadata
                   card-id  :id :as card} (mt/user-http-request
                                           :rasta :post 200
                                           "card"
                                           (assoc (card-with-name-and-query "card-name"
                                                                            query)
                                                  :dataset true))]
              (is (= ["ID" "NAME"] (map norm metadata)))
              (is (= ["EDITED DISPLAY" "EDITED DISPLAY"]
                     (->> (update-card!
                           (assoc card
                                  :result_metadata (map #(assoc % :display_name "EDITED DISPLAY") metadata)))
                          :result_metadata (map :display_name))))
              ;; simulate a user changing the query without rerunning the query
              (is (= ["EDITED DISPLAY" "EDITED DISPLAY" "PRICE"]
                     (->> (update-card! (assoc card
                                               :dataset_query modified-query
                                               :result_metadata (map #(assoc % :display_name "EDITED DISPLAY")
                                                                     metadata)))
                          :result_metadata
                          (map (comp str/upper-case :display_name)))))
              (is (= ["EDITED DISPLAY" "EDITED DISPLAY" "PRICE"]
                     (map (comp str/upper-case :display_name)
                          (db/select-one-field :result_metadata Card :id card-id))))
              (testing "Even if you only send the new query and not existing metadata"
                (is (= ["EDITED DISPLAY" "EDITED DISPLAY"]
                     (->> (update-card! {:id (u/the-id card) :dataset_query query}) :result_metadata (map :display_name)))))
              (testing "Descriptions can be cleared (#20517)"
                (is (= ["foo" "foo"]
                       (->> (update-card! (update card
                                                  :result_metadata (fn [m]
                                                                     (map #(assoc % :description "foo") m))))
                            :result_metadata
                            (map :description))))
                (is (= ["" ""]
                       (->> (update-card! (update card
                                                  :result_metadata (fn [m]
                                                                     (map #(assoc % :description "") m))))
                            :result_metadata
                            (map :description)))))))))))
  (testing "Cards preserve edits to `visibility_type` (#22520)"
    (mt/with-temp* [Card [model {:dataset_query (mt/mbql-query venues
                                                               {:fields [$id $name]
                                                                :limit 2})
                                 :dataset       true}]]
      (let [updated-metadata (-> model :result_metadata vec
                                 (assoc-in [1 :visibility_type]
                                           :details-only))
            response         (mt/user-http-request :crowberto :put 200 (format "card/%d" (u/the-id model))
                                                   (assoc model :result_metadata updated-metadata))]
        ;; check they come back from saving the question
        (is (= "details-only" (-> response :result_metadata last :visibility_type))
            "saving metadata lacks visibility type")
        (let [query-result (mt/user-http-request :crowberto :post 202 (format "card/%d/query"
                                                                              (u/the-id model)))]
          ;; ensure future responses also include them
          (is (= "details-only" (-> query-result
                                    :data :results_metadata :columns last :visibility_type))
              "subsequent query lacks visibility type")
          (is (= "details-only" (-> query-result
                                    :data :cols last :visibility_type))
              "in cols (important for the saved metadata)"))))))

(defn- do-with-persistence-setup [f]
  ;; mt/with-temp-scheduler actually just reuses the current scheduler. The scheduler factory caches by name set in
  ;; the resources/quartz.properties file and we reuse that scheduler
  (let [sched (.getScheduler
               (StdSchedulerFactory. (doto (java.util.Properties.)
                                       (.setProperty "org.quartz.scheduler.instanceName" (str (gensym "card-api-test")))
                                       (.setProperty "org.quartz.scheduler.instanceID" "AUTO")
                                       (.setProperty "org.quartz.properties" "non-existant")
                                       (.setProperty "org.quartz.threadPool.threadCount" "6")
                                       (.setProperty "org.quartz.threadPool.class" "org.quartz.simpl.SimpleThreadPool"))))]
    ;; a binding won't work since we need to cross thread boundaries
    (with-redefs [task/scheduler (constantly sched)]
      (try
        (qs/standby sched)
        (#'task.persist-refresh/job-init!)
        (#'task.sync-databases/job-init)
        (mt/with-temporary-setting-values [:persisted-models-enabled true]
          (mt/with-temp* [Database [db {:options {:persist-models-enabled true}}]]
            (f db)))
        (finally
          (qs/shutdown sched))))))

(defmacro ^:private with-persistence-setup
  "Sets up a temp scheduler, a temp database and enabled persistence. Scheduler will be in standby mode so that jobs
  won't run. Just check for trigger presence."
  [db-binding & body]
  `(do-with-persistence-setup (fn [~db-binding] ~@body)))

(deftest refresh-persistence
  (testing "Can schedule refreshes for models"
    (with-persistence-setup db
      (mt/with-temp* [Card          [unmodeled {:dataset false :database_id (u/the-id db)}]
                      Card          [archived {:dataset true :archived true :database_id (u/the-id db)}]
                      Card          [model {:dataset true :database_id (u/the-id db)}]
                      PersistedInfo [pmodel  {:card_id (u/the-id model) :database_id (u/the-id db)}]
                      PersistedInfo [punmodeled  {:card_id (u/the-id unmodeled) :database_id (u/the-id db)}]
                      PersistedInfo [parchived  {:card_id (u/the-id archived) :database_id (u/the-id db)}]]
        (testing "Can refresh models"
          (mt/user-http-request :crowberto :post 204 (format "card/%d/refresh" (u/the-id model)))
          (is (contains? (task.persist-refresh/job-info-for-individual-refresh)
                         (u/the-id pmodel))
              "Missing refresh of model"))
        (testing "Won't refresh archived models"
          (mt/user-http-request :crowberto :post 400 (format "card/%d/refresh" (u/the-id archived)))
          (is (not (contains? (task.persist-refresh/job-info-for-individual-refresh)
                              (u/the-id punmodeled)))
              "Scheduled refresh of archived model"))
        (testing "Won't refresh cards no longer models"
          (mt/user-http-request :crowberto :post 400 (format "card/%d/refresh" (u/the-id unmodeled)))
          (is (not (contains? (task.persist-refresh/job-info-for-individual-refresh)
                              (u/the-id parchived)))
              "Scheduled refresh of archived model"))))))
