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

Can be set by `config/«dev|production»/config.edn` or environment variables.

### Development

    lein with-profile dev run

### Production

    lein with-profile prod run