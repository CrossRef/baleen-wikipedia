(ns baleen-wikipedia.main
  (:require [baleen-wikipedia.ingest :as ingest]
            [baleen-wikipedia.diff :as diff]
            [baleen-wikipedia.push :as push]
            [baleen-wikipedia.serve :as serve])
  (:gen-class))

; TODO load config.
(def config
  {:subscribe "*.wikipedia.org"})

(defn main-ingest
  "Ingest the RC Stream"
  []
  (prn "INGEST")
  (ingest/run))

(defn main-diff
  "Fetch changes and extract diffs."
  []
  (prn "DIFF")
  (diff/run))

(defn main-push
  "Push diffs to Lagotto."
  []
  (prn "PUSH")
  (push/run))

(defn main-serve
  "Run webserver."
  []
  (prn "SERVE")
  (serve/run))

(defn main-archive
  "Run archiver."
  []
  (prn "ARCHIVE"))

(defn main-unrecognised-action
  [command]
  (prn "ERROR didn't recognise " command))

(defn -main [& args]
  (let [command (first args)]
    (condp = command
      "ingest" (main-ingest)
      "diff" (main-diff)
      "push" (main-push)
      "serve" (main-serve)
      "archive" (main-archive)
      (main-unrecognised-action command))))

