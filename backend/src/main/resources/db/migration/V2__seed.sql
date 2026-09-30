-- Seed data ported from Django migrations 0006, 0010, 0011, 0012.
-- Jobs reference a named task from the in-code TaskRegistry (no dotted import paths).

INSERT INTO jobs (name, job_type, config, schedule_cron) VALUES
  ('attendance_reminders', 'python', '{"task": "send_attendance_reminders", "args": [], "kwargs": {}}'::jsonb, '0 7 * * 1-5'),
  ('system_status_digest', 'python', '{"task": "send_system_status_digest", "args": [], "kwargs": {}}'::jsonb, '0 8 * * *'),
  ('web_scrape_portal', 'python', '{"task": "run_web_scrape", "args": ["admissions_portal"], "kwargs": {}}'::jsonb, '*/30 * * * *'),
  ('nightly_ingest_science', 'python', '{"task": "schedule_file_ingest", "args": ["Science"], "kwargs": {}}'::jsonb, '30 2 * * *'),
  ('weekly_cleanup', 'python', '{"task": "purge_old_records", "args": [90], "kwargs": {}}'::jsonb, '0 3 * * 0'),
  ('daily_backup', 'python', '{"task": "run_daily_backup", "args": [], "kwargs": {}}'::jsonb, '0 1 * * *'),
  ('weekly_ingest_admissions', 'python', '{"task": "schedule_file_ingest", "args": ["Admissions"], "kwargs": {}}'::jsonb, '0 6 * * 1'),
  ('weekly_ingest_attendance', 'python', '{"task": "schedule_file_ingest", "args": ["Attendance"], "kwargs": {}}'::jsonb, '0 7 * * 1'),
  ('monthly_ingest_fees', 'python', '{"task": "schedule_file_ingest", "args": ["Fees"], "kwargs": {}}'::jsonb, '0 5 1 * *'),
  ('weekly_ingest_library', 'python', '{"task": "schedule_file_ingest", "args": ["Library"], "kwargs": {}}'::jsonb, '0 8 * * 2'),
  ('monthly_ingest_examination', 'python', '{"task": "schedule_file_ingest", "args": ["Examination"], "kwargs": {}}'::jsonb, '0 9 1 * *'),
  ('weekly_ingest_all_departments', 'python', '{"task": "schedule_all_department_ingest", "args": [], "kwargs": {}}'::jsonb, '30 6 * * 1')
ON CONFLICT (name) DO NOTHING;

INSERT INTO department_sources (name, code, description, schedule_hint) VALUES
  ('Admissions','ADM','Weekly admissions intake and entrance test results.','Weekly (Mon 06:00)'),
  ('Attendance','ATT','Weekly attendance rollup per student.','Weekly (Mon 07:00)'),
  ('Fees','FEE','Monthly fee ledger updates.','Monthly (1st 05:00)'),
  ('Library','LIB','Weekly library usage and late returns.','Weekly (Tue 08:00)'),
  ('Examination','EXM','Monthly exam score snapshot.','Monthly (1st 09:00)'),
  ('Science','SCI','Nightly science lab score ingest.','Daily (02:30)')
ON CONFLICT (code) DO NOTHING;

INSERT INTO department_records (source_id, student_id, student_name, class_name, score, attendance_percent, status) VALUES
  ((SELECT id FROM department_sources WHERE code='ADM'),'ADM001','Aarav Sharma','10-A',86.5,98.0,'verified'),
  ((SELECT id FROM department_sources WHERE code='ADM'),'ADM002','Neha Patel','10-B',82.0,96.5,'verified'),
  ((SELECT id FROM department_sources WHERE code='ADM'),'ADM003','Kabir Singh','9-A',79.2,94.0,'pending'),
  ((SELECT id FROM department_sources WHERE code='ADM'),'ADM004','Isha Rao','9-B',88.1,97.0,'verified'),
  ((SELECT id FROM department_sources WHERE code='ATT'),'ATT101','Priya Nair','8-A',90.0,93.2,'present'),
  ((SELECT id FROM department_sources WHERE code='ATT'),'ATT102','Arjun Das','8-B',84.0,88.9,'present'),
  ((SELECT id FROM department_sources WHERE code='ATT'),'ATT103','Rohan Mehta','7-A',76.0,91.0,'absent'),
  ((SELECT id FROM department_sources WHERE code='ATT'),'ATT104','Sara Ali','7-B',89.5,95.4,'present'),
  ((SELECT id FROM department_sources WHERE code='FEE'),'FEE201','Vikram Joshi','11-A',91.0,92.0,'paid'),
  ((SELECT id FROM department_sources WHERE code='FEE'),'FEE202','Meera Kapoor','11-B',88.0,90.5,'partial'),
  ((SELECT id FROM department_sources WHERE code='FEE'),'FEE203','Dev Shah','12-A',93.4,94.2,'paid'),
  ((SELECT id FROM department_sources WHERE code='FEE'),'FEE204','Anaya Gupta','12-B',85.6,89.0,'pending'),
  ((SELECT id FROM department_sources WHERE code='LIB'),'LIB301','Ishaan Verma','6-A',78.0,87.5,'overdue'),
  ((SELECT id FROM department_sources WHERE code='LIB'),'LIB302','Nisha Kumar','6-B',80.5,90.0,'returned'),
  ((SELECT id FROM department_sources WHERE code='LIB'),'LIB303','Ravi Menon','5-A',74.2,85.0,'returned'),
  ((SELECT id FROM department_sources WHERE code='LIB'),'LIB304','Tara Bose','5-B',81.0,88.0,'overdue'),
  ((SELECT id FROM department_sources WHERE code='EXM'),'EXM401','Kiran Rao','10-A',92.0,96.0,'pass'),
  ((SELECT id FROM department_sources WHERE code='EXM'),'EXM402','Sameer Khan','10-B',77.5,90.0,'pass'),
  ((SELECT id FROM department_sources WHERE code='EXM'),'EXM403','Anita Roy','9-A',69.0,88.0,'review'),
  ((SELECT id FROM department_sources WHERE code='EXM'),'EXM404','Ritika Jain','9-B',84.7,92.5,'pass'),
  ((SELECT id FROM department_sources WHERE code='SCI'),'SCI501','Om Prakash','11-A',86.0,94.0,'lab-ok'),
  ((SELECT id FROM department_sources WHERE code='SCI'),'SCI502','Diya Sen','11-B',91.5,97.0,'lab-ok'),
  ((SELECT id FROM department_sources WHERE code='SCI'),'SCI503','Yash Arora','12-A',88.2,93.0,'lab-ok'),
  ((SELECT id FROM department_sources WHERE code='SCI'),'SCI504','Pooja Iyer','12-B',79.8,90.0,'lab-review');
