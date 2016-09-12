CREATE TABLE artifact_name (
  id INTEGER PRIMARY KEY NOT NULL AUTO_INCREMENT,
  name VARCHAR(128)
) ENGINE=InnoDB;

CREATE INDEX artifact_name ON artifact_name(name);

CREATE TABLE current_artifact (
  id INTEGER PRIMARY KEY NOT NULL AUTO_INCREMENT,
  artifact_name_id INTEGER NOT NULL,
  FOREIGN KEY (artifact_name_id) REFERENCES artifact_name(id),
  version_id VARCHAR(32), # MD5 hash of content
  updated DATETIME,
  link TEXT
) ENGINE=InnoDB;

CREATE INDEX current_artifact_name_id ON current_artifact(artifact_name_id);
CREATE UNIQUE INDEX current_artifact_unique ON current_artifact(artifact_name_id);

CREATE TABLE historical_artifact (
  id INTEGER PRIMARY KEY NOT NULL AUTO_INCREMENT,
  artifact_name_id INTEGER NOT NULL,
  FOREIGN KEY (artifact_name_id) REFERENCES artifact_name(id),
  version_id VARCHAR(32), # MD5 hash of content
  updated DATETIME,
  link TEXT
) ENGINE=InnoDB;

CREATE INDEX historical_artifact_name_id ON historical_artifact(artifact_name_id);


CREATE TABLE evidence (
  id INTEGER PRIMARY KEY NOT NULL AUTO_INCREMENT,
  version_id VARCHAR(32), # MD5 hash of content
  data TEXT,
  processed BOOLEAN
) ENGINE=InnoDB;

CREATE INDEX evidence_version_id ON evidence(version_id);

CREATE TABLE event (
  id INTEGER PRIMARY KEY NOT NULL AUTO_INCREMENT,
  event_id VARCHAR(32), # UUID
  data TEXT,
  processed BOOLEAN
) ENGINE=InnoDB;

CREATE INDEX evidence_id ON event(event_id);

CREATE TABLE event_evidence (
  id INTEGER PRIMARY KEY NOT NULL AUTO_INCREMENT,
  event_id INTEGER,
  evidence_id INTEGER,
  FOREIGN KEY (event_id) REFERENCES event(id),
  FOREIGN KEY (evidence_id) REFERENCES evidence(id)
) ENGINE=InnoDB;

CREATE INDEX event_evidence_event_id ON event_evidence(event_id);

