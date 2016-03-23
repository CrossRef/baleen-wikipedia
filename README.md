# Baleen Wikipedia edition

Agent that monitors diffs in Wikipedia and reports DOI citations and uncitations to production Crossref Event Data. Successor to Baleen prototype, uses a message queue.

Uses ActiveMQ and could be run on AWS.

## Usage

This tool runs in several stages.

### ingest

Monitor Wikipedia Recent Changes stream and place new page changes on the message queue.

### diff

Take items off the page changes message queue, fetches and compares revisions, extracts DOIs, places in queues for push, serve and archive.

### push

Takes events off the message queue and pushes them into Crossref Event Data Lagotto instance.

### serve

Takes events off the message queue and serves them up in a web interface.

### archive

Takes events off the message queue and saves them to storage.

## Run in development

`activemq console`

http://localhost:8161/admin/ 

admin/admin

`lein run ingest`

`lein run diff`

`lein run push`

`lein run serve`

## Installation

The Wikipedia Recent Changes stream uses an obsolete version of Socket.IO ([details](https://phabricator.wikimedia.org/T68232)), so we need to use an unsupported version. To install:

 - download and build per instructions at https://github.com/Gottox/socket.io-java-client
 - put the JAR in `lib`
 - `lein localrepo install lib/socketio.jar gottox/socketio "0.1"`
 - `lein run`

## TODO

- confirm correct schema for integration with Lagotto
- full configuration
- message queue transactions
- store events
- integrate with AWS

## License

Copyright © 2016 Crossref

