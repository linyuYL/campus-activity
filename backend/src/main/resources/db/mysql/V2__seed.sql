INSERT INTO account(id,display_name,student_no_last4) VALUES (1,'演示学生甲','0001'),(2,'演示学生乙','0002'),(3,'社团负责人',NULL),(4,'团委老师',NULL),(5,'系统管理员',NULL),(6,'其他社团负责人',NULL);
INSERT INTO club(id,name) VALUES (1,'读书社'),(2,'科技社');
INSERT INTO category VALUES ('LECTURE','讲座'),('CULTURE','文化'),('SPORT','体育');
INSERT INTO role_grant(account_id,role,scope_key,changed_by,changed_at) VALUES (3,'CLUB_MANAGER',1,5,CURRENT_TIMESTAMP),(4,'REVIEWER',1,5,CURRENT_TIMESTAMP),(5,'SYSTEM_ADMIN',0,5,CURRENT_TIMESTAMP),(6,'CLUB_MANAGER',2,5,CURRENT_TIMESTAMP);
