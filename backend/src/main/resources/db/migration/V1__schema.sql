CREATE TABLE account (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 display_name VARCHAR(80) NOT NULL,
 student_no_last4 CHAR(4),
 student_no_digest VARCHAR(64) UNIQUE
);
CREATE TABLE club (id BIGINT PRIMARY KEY AUTO_INCREMENT, name VARCHAR(100) NOT NULL);
CREATE TABLE category (code VARCHAR(32) PRIMARY KEY, name VARCHAR(40) NOT NULL);
CREATE TABLE role_grant (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 account_id BIGINT NOT NULL,
 role VARCHAR(32) NOT NULL,
 scope_key BIGINT NOT NULL DEFAULT 0,
 status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
 active_marker INT GENERATED ALWAYS AS (CASE WHEN status='ACTIVE' THEN 1 ELSE NULL END),
 changed_by BIGINT NOT NULL,
 changed_at TIMESTAMP(6) NOT NULL,
 UNIQUE(account_id, role, scope_key, active_marker),
 CHECK(role IN ('CLUB_MANAGER','REVIEWER','SYSTEM_ADMIN')),
 CHECK(status IN ('ACTIVE','REVOKED')),
 CHECK((role='SYSTEM_ADMIN' AND scope_key=0) OR (role<>'SYSTEM_ADMIN' AND scope_key>0)),
 FOREIGN KEY (account_id) REFERENCES account(id) ON DELETE RESTRICT,
 FOREIGN KEY (changed_by) REFERENCES account(id) ON DELETE RESTRICT
);
CREATE TABLE activity (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 club_id BIGINT NOT NULL,
 title VARCHAR(100) NOT NULL,
 category VARCHAR(32) NOT NULL,
 location VARCHAR(200) NOT NULL,
 description VARCHAR(4000) NOT NULL,
 poster_ref VARCHAR(255),
 capacity INT NOT NULL,
 registered_count INT NOT NULL DEFAULT 0,
 registration_closes_at TIMESTAMP(6) NOT NULL,
 starts_at TIMESTAMP(6) NOT NULL,
 ends_at TIMESTAMP(6) NOT NULL,
 status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
 created_by BIGINT NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL,
 published_at TIMESTAMP(6),
 cancelled_by BIGINT,
 cancelled_at TIMESTAMP(6),
 version BIGINT NOT NULL DEFAULT 1,
 CHECK(capacity>0 AND registered_count>=0 AND registered_count<=capacity),
 CHECK(registration_closes_at<starts_at AND starts_at<ends_at),
 CHECK(status IN ('DRAFT','PENDING_REVIEW','RETURNED','PUBLISHED','CANCELLED')),
 FOREIGN KEY (club_id) REFERENCES club(id) ON DELETE RESTRICT,
 FOREIGN KEY (category) REFERENCES category(code) ON DELETE RESTRICT,
 FOREIGN KEY (created_by) REFERENCES account(id) ON DELETE RESTRICT,
 FOREIGN KEY (cancelled_by) REFERENCES account(id) ON DELETE RESTRICT
);
CREATE INDEX activity_start ON activity(status,starts_at,id);
CREATE INDEX activity_published ON activity(status,published_at DESC,id);
CREATE INDEX activity_club ON activity(club_id,updated_at DESC,id);
CREATE TABLE submission (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 activity_id BIGINT NOT NULL,
 submitted_by BIGINT NOT NULL,
 submitted_at TIMESTAMP(6) NOT NULL,
 status VARCHAR(16) NOT NULL,
 CHECK(status IN ('PENDING','APPROVED','RETURNED')),
 FOREIGN KEY (activity_id) REFERENCES activity(id) ON DELETE RESTRICT,
 FOREIGN KEY (submitted_by) REFERENCES account(id) ON DELETE RESTRICT
);
CREATE INDEX submission_pending ON submission(status,submitted_at,id);
CREATE INDEX submission_history ON submission(activity_id,submitted_at DESC,id);
CREATE TABLE review (
 submission_id BIGINT PRIMARY KEY,
 decision VARCHAR(16) NOT NULL,
 comment VARCHAR(500),
 reviewed_by BIGINT NOT NULL,
 reviewed_at TIMESTAMP(6) NOT NULL,
 CHECK(decision IN ('APPROVED','RETURNED')),
 FOREIGN KEY (submission_id) REFERENCES submission(id) ON DELETE RESTRICT,
 FOREIGN KEY (reviewed_by) REFERENCES account(id) ON DELETE RESTRICT
);
CREATE TABLE registration (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 activity_id BIGINT NOT NULL,
 student_id BIGINT NOT NULL,
 status VARCHAR(16) NOT NULL DEFAULT 'REGISTERED',
 active_marker INT GENERATED ALWAYS AS (CASE WHEN status IN ('REGISTERED','CHECKED_IN') THEN 1 ELSE NULL END),
 registered_at TIMESTAMP(6) NOT NULL,
 cancelled_at TIMESTAMP(6),
 checked_in_at TIMESTAMP(6),
 checked_in_by BIGINT,
 UNIQUE(activity_id,student_id,active_marker),
 CHECK(status IN ('REGISTERED','CANCELLED','CHECKED_IN')),
 FOREIGN KEY (activity_id) REFERENCES activity(id) ON DELETE RESTRICT,
 FOREIGN KEY (student_id) REFERENCES account(id) ON DELETE RESTRICT,
 FOREIGN KEY (checked_in_by) REFERENCES account(id) ON DELETE RESTRICT
);
CREATE INDEX registration_person ON registration(student_id,registered_at DESC,id);
CREATE INDEX registration_roster ON registration(activity_id,registered_at DESC,id);
CREATE TABLE api_session (
 token_hash CHAR(64) PRIMARY KEY,
 account_id BIGINT NOT NULL,
 expires_at TIMESTAMP(6) NOT NULL,
 FOREIGN KEY (account_id) REFERENCES account(id) ON DELETE RESTRICT
);
CREATE TABLE idempotency (
 actor_id BIGINT NOT NULL,
 operation VARCHAR(240) NOT NULL,
 request_key VARCHAR(128) NOT NULL,
 body_hash CHAR(64) NOT NULL,
 response_json TEXT,
 response_status INT,
 response_location VARCHAR(255),
 created_at TIMESTAMP(6) NOT NULL,
 PRIMARY KEY(actor_id,operation,request_key),
 FOREIGN KEY (actor_id) REFERENCES account(id) ON DELETE RESTRICT
);
CREATE TABLE audit_log (
 id BIGINT PRIMARY KEY AUTO_INCREMENT,
 actor_id BIGINT NOT NULL,
 operation VARCHAR(240) NOT NULL,
 object_id VARCHAR(64),
 occurred_at TIMESTAMP(6) NOT NULL,
 FOREIGN KEY (actor_id) REFERENCES account(id) ON DELETE RESTRICT
);
