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
 - `:db-host` MySQL hostname
 - `:db-port` MySQL port as a string
 - `:port` port to run service on, as a string
 - `:service-base` base of the service e.g. "http://localhost:9999" for development, "http://evidence.eventdata.crossref.org" for production
 - `:auth-tokens` comma-separated tokens for pushing data "TOKEN1,TOKEN2,TOKEN3"
 
 External services

 - `:status-service-base` base of the Status Service, e.g. "http://localhost:9998"
 - `:status-service-auth-token` Status Service auth token, e.g. "TOKEN1"
 - `:s3-access-key-id` 
 - `:s3-secret-access-key`
 - `:archive-base-url` public face of the S3 storage bucket "http://archive-test.eventdata.crossref.org"
 - `:archive-bucket` S3 bucket e.g. "archive-text.eventdata.crossref.org"
 - `:send-deposits-to-lagotto` send deposits to Lagotto? False during development. In production enable only on one instance.
 - `:lagotto-service-auth-token` auth token for Lagotto
 - `:lagotto-service-base` - URL of Lagotto service.

### Extra for development:

 - `:disable-deposit` Don't send Deposits to Lagotto. Useful when just running in mock mode.

Can be set by `config/«dev|production»/config.edn` or environment variables.

### Development

    lein with-profile dev run

### Production

    lein with-profile prod run