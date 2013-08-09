(ns ring.adapter.vertx
  "Adapter for the vertx webserver."
  (:import (java.io File InputStream FileInputStream
                    OutputStream ByteArrayOutputStream
                    ByteArrayInputStream))
  (:require [vertx.http :as http]
            [vertx.buffer :as buf]
            [clojure.java.io :as io]
            [clojure.string :as string]))

(defn- get-headers
  "Creates a name/value map of all the request headers."
  [req]
  (let [headers (.headers req)]
    (into {} (for [m (.entries headers)]
               {(key m) (string/split (val m) #",")}))))

(defn set-status
  "Update a HttpServerResponse with a status code."
  [resp status]
  (.setStatusCode resp status))

(defn set-headers
  "Update a HttpServerResponse with a map of headers."
  [resp headers]
  (doseq [[key val-or-vals] headers]
    (.putHeader resp key val-or-vals)))

(defn- set-body
  "Update a HttpServerResponse body with a String, ISeq, File or InputStream."
  [resp body]
  (cond
   (string? body) (http/end resp body)

   ;;TODO: i am not sure the way is righit following code
   (seq? body) (do
                 (.setChunked true resp)
                 (doseq [chunk body]
                   (http/end resp chunk)))

   (instance? InputStream body)
   (with-open [^InputStream in body
               ^OutputStream out (ByteArrayOutputStream.)]
     (io/copy in out)
     (http/end resp (buf/buffer (.toByteArray out))))

   (instance? File body) (let [^File f body]
                           (with-open [stream (FileInputStream. f)]
                             (set-body resp stream)))
   (nil? body) nil
   :else
   (throw (Exception. ^String (format "Unrecognized body: %s" body)))))

(defn- get-content-type
  "Get the content type from header."
  [header]
  (let [ct (first (get header "Accept"))]
    (if (nil? ct) "text/plain" ct)))

(defn- get-char-encoding
  "Get the character encoding"
  [header]
  (let [e (first (get header "Accept-Language"))]
    (if (nil? e) "UTF-8" e)))

(defn- update-response
  "Update ring response to vertx response"
  [resp, {:keys [status headers body]}]
  (when-not resp
    (throw (Exception. "Null response given.")))
  (when status
    (set-status resp status))
  (doto resp
    (set-headers headers)
    (set-body body)))

(defn- build-request-map
  "Return ring request with Vertx's Web parameter"
  [req data]
  (let [header (get-headers req)]
    {:server-port        (-> req (.absoluteURI) (.getPort))
     :server-name        (-> req (.absoluteURI) (.getHost))
     :remote-addr        (-> req (.remoteAddress) (.getHostName))
     :uri                (.path req)
     :query-string       (.query req)
     :scheme             (keyword (-> req (.absoluteURI) (.getScheme)))
     :request-method     (keyword (.toLowerCase (.method req)))
     :headers            header
     :content-type       (get-content-type header)
     :content-length     (or (.length buf) nil)
     :character-encoding (get-char-encoding header)
     :ssl-client-cert    (first (http/certs req))
     :body               (ByteArrayInputStream. (buf/get-bytes data))}))

(defn- request-handler
  "Vertx Handler implementation for the given Ring handler."
  [handler req]
  (on-body req (fn [data]
                 (when-let [response-map
                            (handler (build-request-map req data))]
                   (update-response (http/server-response req) response-map)))))

(defn run-vertx-web
  [http-server handler host port & {:as options}]
  (doto http-server
    (http/on-request (request-handler handler))
    (http/listen port host))
  http-server)