# Event Data Evidence Service

Keep track of Evidence and link it to Events.

Status: Under development September 2016.

## Running

### Requirements

 - MySQL database

### Configuration values:

 - `:db-name` MySQL database name
 - `:db-user` MySQL database username
 - `:db-password` MySQL database password
 - `:port` port to run service on, as a string
 - `:service-base` base of the service e.g. "http://localhost:9999" for development, "http://evidence.eventdata.crossref.org" for production
 - `:auth-tokens` comma-separated tokens for pushing data "TOKEN1,TOKEN2,TOKEN3"
 
 External services

 - `:status-service-base` base of the Status Service, e.g. "http://localhost:9998"
 - `:status-service-auth-token` Status Service auth token, e.g. "TOKEN1"
 - `:s3-access-key-id` 
 - `:s3-secret-access-key`
 - `:archive-base-url` public face of the storage bucket "http://archive-test.eventdata.crossref.org"
 - `:archive-bucket` "archive-text.eventdata.crossref.org"
 

### Extra for development:

 - `:disable-deposit` Don't send Deposits to Lagotto. Useful when just running in mock mode.

Can be set by `config/«dev|production»/config.edn` or environment variables.

### Development

    lein with-profile dev run

### Production

    lein with-profile prod run