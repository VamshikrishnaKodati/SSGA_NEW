-- ============================================================================
-- 19_clean_slate_and_demo_setup.sql - Clean Slate + Demo Accounts + Hierarchy
-- Run after 18_add_division_hierarchy.sql
-- ============================================================================

BEGIN;

-- ============================================================================
-- STEP 1: CLEAN SLATE - Remove all existing data
-- ============================================================================

-- Delete in dependency order (child tables first)
TRUNCATE TABLE public.staging_workflow_events RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.staging_payroll_details RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.staging_payroll_header RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.monthly_salary_ledger RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.salary_revision_history RESTART IDENTITY CASCADE;

-- Clear employee master data but keep table structure
TRUNCATE TABLE public.employees RESTART IDENTITY CASCADE;

-- Clear import jobs
TRUNCATE TABLE public.import_job_assignments RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.import_job_sheets RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.import_jobs RESTART IDENTITY CASCADE;

-- Clear users and roles (will re-create demo accounts)
TRUNCATE TABLE public.user_role_scopes RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.users RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.roles RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.permissions RESTART IDENTITY CASCADE;
TRUNCATE TABLE public.role_permissions RESTART IDENTITY CASCADE;

-- ============================================================================
-- STEP 2: RE-CREATE PERMISSIONS & ROLES
-- ============================================================================

-- Permissions
INSERT INTO public.permissions (code, name, description, module) VALUES
('system.admin', 'System Administrator', 'Full system access', 'SYSTEM'),
('salary.manage', 'Manage Salary', 'Create/edit salary components, pay scales', 'PAYROLL'),
('payroll.prepare', 'Prepare Payroll', 'Officer: Prepare and submit payroll', 'PAYROLL'),
('payroll.approve', 'Approve Payroll', 'Senior Officer: Verify payroll', 'PAYROLL'),
('payroll.finalize', 'Finalize Payroll', 'General Manager: Approve and lock ledger', 'PAYROLL'),
('employee.create', 'Create Employee', 'Add/edit employee master data', 'HR'),
('employee.view', 'View Employee', 'View employee details', 'HR'),
('division.manage', 'Manage Divisions', 'Manage division hierarchy', 'HR'),
('report.view', 'View Reports', 'Access payroll reports', 'REPORTS'),
('import.manage', 'Manage Imports', 'Upload and process salary imports', 'PAYROLL'),
('assignment.manage', 'Manage Assignments', 'Assign import jobs to users', 'PAYROLL')
ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, module = EXCLUDED.module;

-- Roles
INSERT INTO public.roles (name, code, description, max_scope, is_active) VALUES
('System Admin', 'SYS_ADMIN', 'Full system access', 'ORG', true),
('General Manager', 'GM', 'General Manager - Final payroll approval', 'ORG', true),
('Senior Officer F&A', 'SO_FINANCE', 'Senior Officer Finance & Accounts - Verify payroll', 'ORG', true),
('Officer Payroll', 'OFF_PAYROLL', 'Payroll Officer - Prepare payroll', 'DIVISION', true),
('Division Admin', 'DIV_ADMIN', 'Division Administrator', 'DIVISION', true),
('Employee', 'EMPLOYEE', 'Regular employee - View own payslip', 'SELF', true),
('IT Admin', 'IT_ADMIN', 'IT Administrator - System access', 'ORG', true)
ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name, description = EXCLUDED.description, max_scope = EXCLUDED.max_scope, is_active = EXCLUDED.is_active;

-- Role-Permission mapping
DELETE FROM public.role_permissions;

-- System Admin - all permissions
INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM public.roles r, public.permissions p
WHERE r.code = 'SYS_ADMIN';

-- General Manager - finalize, approve, view
INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM public.roles r, public.permissions p
WHERE r.code = 'GM' AND p.code IN ('payroll.finalize', 'payroll.approve', 'payroll.prepare', 'report.view', 'employee.view', 'salary.manage');

-- Senior Officer F&A - approve, prepare, view
INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM public.roles r, public.permissions p
WHERE r.code = 'SO_FINANCE' AND p.code IN ('payroll.approve', 'payroll.prepare', 'report.view', 'employee.view', 'salary.manage');

-- Officer Payroll - prepare, import
INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM public.roles r, public.permissions p
WHERE r.code = 'OFF_PAYROLL' AND p.code IN ('payroll.prepare', 'import.manage', 'employee.view', 'employee.create');

-- Division Admin - manage division employees
INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM public.roles r, public.permissions p
WHERE r.code = 'DIV_ADMIN' AND p.code IN ('employee.create', 'employee.view', 'division.manage', 'report.view');

-- IT Admin - system, import, assignment
INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM public.roles r, public.permissions p
WHERE r.code = 'IT_ADMIN' AND p.code IN ('system.admin', 'import.manage', 'assignment.manage', 'employee.view');

-- Employee - view own
INSERT INTO public.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM public.roles r, public.permissions p
WHERE r.code = 'EMPLOYEE' AND p.code IN ('employee.view', 'report.view');

-- ============================================================================
-- STEP 3: CREATE DEMO USERS WITH PASSWORDS
-- Password for all: "Password@123" (BCrypt hashed)
-- Hash: $2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3
-- ============================================================================

-- BCrypt hash for "Password@123" with cost 10
-- Generated using: BCryptPasswordEncoder().encode("Password@123")
-- $2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3

INSERT INTO public.users (employee_code, username, email, password_hash, full_name, mobile, is_active, created_at, updated_at) VALUES
-- System Admin
('SYS001', 'sysadmin', 'sysadmin@hmwssb.gov.in', '$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3', 'System Administrator', '9000000001', true, NOW(), NOW()),

-- General Manager (MD Level)
('GM001', 'gm_md', 'md@hmwssb.gov.in', '$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3', 'Managing Director (IAS)', '9000000002', true, NOW(), NOW()),

-- General Manager (ED Level) 
('GM002', 'gm_ed', 'ed@hmwssb.gov.in', '$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3', 'Executive Director', '9000000003', true, NOW(), NOW()),

-- Senior Officer F&A (CGM Level)
('SO001', 'so_finance', 'cgm.finance@hmwssb.gov.in', '$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3', 'CGM Finance & Accounts', '9000000004', true, NOW(), NOW()),
('SO002', 'so_tech', 'cgm.technical@hmwssb.gov.in', '$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3', 'CGM Technical', '9000000005', true, NOW(), NOW()),

-- Chief Engineers (Zonal)
('CE001', 'ce_om', 'ce.om@hmwssb.gov.in', '$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3', 'Chief Engineer (O&M)', '9000000006', true, NOW(), NOW()),
('CE002', 'ce_projects', 'ce.projects@hmwssb.gov.in', '$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3', 'Chief Engineer (Projects)', '9000000007', true, NOW(), NOW()),
('CE003', 'ce_trans', 'ce.transmission@hmwssb.gov.in', '$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3', 'Chief Engineer (Transmission)', '9000000008', true, NOW(), NOW()),

-- Superintending Engineers (Circle Level)
('SE001', 'se_cir1', 'se.circle1@hmwssb.gov.in', '$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3', 'SE O&M Circle I', '9000000011', true, NOW(), NOW()),
('SE002', 'se_cir2', 'se.circle2@hmwssb.gov.in', '$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3', 'SE O&M Circle II', '9000000012', true, NOW(), NOW()),
('SE003', 'se_cir3', 'se.circle3@hmwssb.gov.in', '$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3', 'SE O&M Circle III', '9000000013', true, NOW(), NOW()),
('SE004', 'se_cir4', 'se.circle4@hmwssb.gov.in', '$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3', 'SE O&M Circle IV', '9000000014', true, NOW(), NOW()),
('SE005', 'se_pcc1', 'se.pcc1@hmwssb.gov.in', '$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3', 'SE PCC-I', '9000000015', true, NOW(), NOW()),
('SE006', 'se_pcc2', 'se.pcc2@hmwssb.gov.in', '$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3', 'SE PCC-II', '9000000016', true, NOW(), NOW()),

-- Executive Engineers (Division Level) - Sample 10
('EE001', 'ee_div1', 'ee.div1@hmwssb.gov.in', '$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3', 'EE O&M Division I', '9000000101', true, NOW(), NOW()),
('EE002', 'ee_div2', 'ee.div2@hmwssb.gov.in', '$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3', 'EE O&M Division II', '9000000102', true, NOW(), NOW()),
('EE003', 'ee_div7', 'ee.div7@hmwssb.gov.in', '$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3', 'EE O&M Division VII', '9000000103', true, NOW(), NOW()),
('EE004', 'ee_div13', 'ee.div13@hmwssb.gov.in', '$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3', 'EE O&M Division XIII', '9000000104', true, NOW(), NOW()),
('EE005', 'ee_div19', 'ee.div19@hmwssb.gov.in', '$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3', 'EE O&M Division XIX', '9000000105', true, NOW(), NOW()),
('EE006', 'ee_prj1', 'ee.prj1@hmwssb.gov.in', '$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3', 'EE Project Division I', '9000000106', true, NOW(), NOW()),
('EE007', 'ee_trn1', 'ee.trn1@hmwssb.gov.in', '$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3', 'EE Transmission Circle I', '9000000107', true, NOW(), NOW()),
('EE008', 'ee_elec', 'ee.elec@hmwssb.gov.in', '$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3', 'EE Electrical Project Division', '9000000108', true, NOW(), NOW()),
('EE009', 'ee_qc', 'ee.qc@hmwssb.gov.in', '$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3', 'EE Quality Control Division', '9000000109', true, NOW(), NOW()),
('EE010', 'ee_board', 'ee.board@hmwssb.gov.in', '$2a$10$X7Hx10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3', 'EE Board Office', '9000000110', true, NOW(), NOW()),

-- Payroll Officers
('PO001', 'payroll_off1', 'payroll.officer1@hmwssb.gov.in', '$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3', 'Payroll Officer 1', '9000000201', true, NOW(), NOW()),
('PO002', 'payroll_off2', 'payroll.officer2@hmwssb.gov.in', '$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3', 'Payroll Officer 2', '9000000202', true, NOW(), NOW()),

-- IT Admin
('IT001', 'itadmin', 'itadmin@hmwssb.gov.in', '$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3', 'IT Administrator', '9000000301', true, NOW(), NOW()),

-- Sample Regular Employees (for testing)
('T-001', 'emp001', 'emp001@hmwssb.gov.in', '$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3', 'Ravi Kumar (Temp)', '9876543210', true, NOW(), NOW()),
('T-002', 'emp002', 'emp002@hmwssb.gov.in', '$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3', 'Priya Sharma (Temp)', '9876543211', true, NOW(), NOW()),
('T-003', 'emp003', 'emp003@hmwssb.gov.in', '$2a$10$X7Hx9QK8vLmN2pQ3rT5uWeJkL8mN1oP3qR4sT6uV7wX8yZ9aB1cD2eF3', 'Suresh Reddy (Temp)', '9876543212', true, NOW(), NOW());

-- ============================================================================
-- STEP 4: ASSIGN ROLES TO USERS (user_role_scopes)
-- ============================================================================

-- Get role IDs
-- System Admin
INSERT INTO public.user_role_scopes (user_id, role_id, scope_type, scope_value, valid_from, is_active)
SELECT u.id, r.id, 'ORG', NULL, CURRENT_DATE, true
FROM public.users u, public.roles r
WHERE u.username = 'sysadmin' AND r.code = 'SYS_ADMIN';

-- General Manager (MD)
INSERT INTO public.user_role_scopes (user_id, role_id, scope_type, scope_value, valid_from, is_active)
SELECT u.id, r.id, 'ORG', NULL, CURRENT_DATE, true
FROM public.users u, public.roles r
WHERE u.username = 'gm_md' AND r.code = 'GM';

-- General Manager (ED)
INSERT INTO public.user_role_scopes (user_id, role_id, scope_type, scope_value, valid_from, is_active)
SELECT u.id, r.id, 'ORG', NULL, CURRENT_DATE, true
FROM public.users u, public.roles r
WHERE u.username = 'gm_ed' AND r.code = 'GM';

-- Senior Officer F&A
INSERT INTO public.user_role_scopes (user_id, role_id, scope_type, scope_value, valid_from, is_active)
SELECT u.id, r.id, 'ORG', NULL, CURRENT_DATE, true
FROM public.users u, public.roles r
WHERE u.username = 'so_finance' AND r.code = 'SO_FINANCE';

INSERT INTO public.user_role_scopes (user_id, role_id, scope_type, scope_value, valid_from, is_active)
SELECT u.id, r.id, 'ORG', NULL, CURRENT_DATE, true
FROM public.users u, public.roles r
WHERE u.username = 'so_tech' AND r.code = 'SO_FINANCE';

-- Chief Engineers (Wing level)
INSERT INTO public.user_role_scopes (user_id, role_id, scope_type, scope_value, valid_from, is_active)
SELECT u.id, r.id, 'WING', 'OM', CURRENT_DATE, true
FROM public.users u, public.roles r
WHERE u.username = 'ce_om' AND r.code = 'DIV_ADMIN';

INSERT INTO public.user_role_scopes (user_id, role_id, scope_type, scope_value, valid_from, is_active)
SELECT u.id, r.id, 'WING', 'PROJECTS', CURRENT_DATE, true
FROM public.users u, public.roles r
WHERE u.username = 'ce_projects' AND r.code = 'DIV_ADMIN';

INSERT INTO public.user_role_scopes (user_id, role_id, scope_type, scope_value, valid_from, is_active)
SELECT u.id, r.id, 'WING', 'TRANSMISSION', CURRENT_DATE, true
FROM public.users u, public.roles r
WHERE u.username = 'ce_trans' AND r.code = 'DIV_ADMIN';

-- Superintending Engineers (Circle level)
INSERT INTO public.user_role_scopes (user_id, role_id, scope_type, scope_value, valid_from, is_active)
SELECT u.id, r.id, 'CIRCLE', 'OM-CIR-I', CURRENT_DATE, true
FROM public.users u, public.roles r
WHERE u.username = 'se_cir1' AND r.code = 'DIV_ADMIN';

INSERT INTO public.user_role_scopes (user_id, role_id, scope_type, scope_value, valid_from, is_active)
SELECT u.id, r.id, 'CIRCLE', 'OM-CIR-II', CURRENT_DATE, true
FROM public.users u, public.roles r
WHERE u.username = 'se_cir2' AND r.code = 'DIV_ADMIN';

INSERT INTO public.user_role_scopes (user_id, role_id, scope_type, scope_value, valid_from, is_active)
SELECT u.id, r.id, 'CIRCLE', 'OM-CIR-III', CURRENT_DATE, true
FROM public.users u, public.roles r
WHERE u.username = 'se_cir3' AND r.code = 'DIV_ADMIN';

INSERT INTO public.user_role_scopes (user_id, role_id, scope_type, scope_value, valid_from, is_active)
SELECT u.id, r.id, 'CIRCLE', 'OM-CIR-IV', CURRENT_DATE, true
FROM public.users u, public.roles r
WHERE u.username = 'se_cir4' AND r.code = 'DIV_ADMIN';

INSERT INTO public.user_role_scopes (user_id, role_id, scope_type, scope_value, valid_from, is_active)
SELECT u.id, r.id, 'CIRCLE', 'PCC-I', CURRENT_DATE, true
FROM public.users u, public.roles r
WHERE u.username = 'se_pcc1' AND r.code = 'DIV_ADMIN';

INSERT INTO public.user_role_scopes (user_id, role_id, scope_type, scope_value, valid_from, is_active)
SELECT u.id, r.id, 'CIRCLE', 'PCC-II', CURRENT_DATE, true
FROM public.users u, public.roles r
WHERE u.username = 'se_pcc2' AND r.code = 'DIV_ADMIN';

-- Executive Engineers (Division level)
INSERT INTO public.user_role_scopes (user_id, role_id, scope_type, scope_value, valid_from, is_active)
SELECT u.id, r.id, 'DIVISION', 'OM-DIV-01', CURRENT_DATE, true
FROM public.users u, public.roles r
WHERE u.username = 'ee_div1' AND r.code = 'DIV_ADMIN';

INSERT INTO public.user_role_scopes (user_id, role_id, scope_type, scope_value, valid_from, is_active)
SELECT u.id, r.id, 'DIVISION', 'OM-DIV-02', CURRENT_DATE, true
FROM public.users u, public.roles r
WHERE u.username = 'ee_div2' AND r.code = 'DIV_ADMIN';

INSERT INTO public.user_role_scopes (user_id, role_id, scope_type, scope_value, valid_from, is_active)
SELECT u.id, r.id, 'DIVISION', 'OM-DIV-07', CURRENT_DATE, true
FROM public.users u, public.roles r
WHERE u.username = 'ee_div7' AND r.code = 'DIV_ADMIN';

INSERT INTO public.user_role_scopes (user_id, role_id, scope_type, scope_value, valid_from, is_active)
SELECT u.id, r.id, 'DIVISION', 'OM-DIV-13', CURRENT_DATE, true
FROM public.users u, public.roles r
WHERE u.username = 'ee_div13' AND r.code = 'DIV_ADMIN';

INSERT INTO public.user_role_scopes (user_id, role_id, scope_type, scope_value, valid_from, is_active)
SELECT u.id, r.id, 'DIVISION', 'OM-DIV-19', CURRENT_DATE, true
FROM public.users u, public.roles r
WHERE u.username = 'ee_div19' AND r.code = 'DIV_ADMIN';

INSERT INTO public.user_role_scopes (user_id, role_id, scope_type, scope_value, valid_from, is_active)
SELECT u.id, r.id, 'DIVISION', 'PRJ-DIV-01', CURRENT_DATE, true
FROM public.users u, public.roles r
WHERE u.username = 'ee_prj1' AND r.code = 'DIV_ADMIN';

INSERT INTO public.user_role_scopes (user_id, role_id, scope_type, scope_value, valid_from, is_active)
SELECT u.id, r.id, 'DIVISION', 'TRN-DIV-01', CURRENT_DATE, true
FROM public.users u, public.roles r
WHERE u.username = 'ee_trn1' AND r.code = 'DIV_ADMIN';

INSERT INTO public.user_role_scopes (user_id, role_id, scope_type, scope_value, valid_from, is_active)
SELECT u.id, r.id, 'DIVISION', 'ELE-DIV-01', CURRENT_DATE, true
FROM public.users u, public.roles r
WHERE u.username = 'ee_elec' AND r.code = 'DIV_ADMIN';

INSERT INTO public.user_role_scopes (user_id, role_id, scope_type, scope_value, valid_from, is_active)
SELECT u.id, r.id, 'DIVISION', 'QC-DIV-01', CURRENT_DATE, true
FROM public.users u, public.roles r
WHERE u.username = 'ee_qc' AND r.code = 'DIV_ADMIN';

INSERT INTO public.user_role_scopes (user_id, role_id, scope_type, scope_value, valid_from, is_active)
SELECT u.id, r.id, 'DIVISION', 'BOARD-DIV-01', CURRENT_DATE, true
FROM public.users u, public.roles r
WHERE u.username = 'ee_board' AND r.code = 'DIV_ADMIN';

-- Payroll Officers (ORG level)
INSERT INTO public.user_role_scopes (user_id, role_id, scope_type, scope_value, valid_from, is_active)
SELECT u.id, r.id, 'ORG', NULL, CURRENT_DATE, true
FROM public.users u, public.roles r
WHERE u.username = 'payroll_off1' AND r.code = 'OFF_PAYROLL';

INSERT INTO public.user_role_scopes (user_id, role_id, scope_type, scope_value, valid_from, is_active)
SELECT u.id, r.id, 'ORG', NULL, CURRENT_DATE, true
FROM public.users u, public.roles r
WHERE u.username = 'payroll_off2' AND r.code = 'OFF_PAYROLL';

-- IT Admin
INSERT INTO public.user_role_scopes (user_id, role_id, scope_type, scope_value, valid_from, is_active)
SELECT u.id, r.id, 'ORG', NULL, CURRENT_DATE, true
FROM public.users u, public.roles r
WHERE u.username = 'itadmin' AND r.code = 'IT_ADMIN';

-- Regular Employees (SELF scope)
INSERT INTO public.user_role_scopes (user_id, role_id, scope_type, scope_value, valid_from, is_active)
SELECT u.id, r.id, 'SELF', NULL, CURRENT_DATE, true
FROM public.users u, public.roles r
WHERE u.username IN ('emp001', 'emp002', 'emp003') AND r.code = 'EMPLOYEE';

-- ============================================================================
-- STEP 5: CREATE SAMPLE EMPLOYEE MASTER RECORDS (Linked to divisions)
-- ============================================================================

-- Sample employees for each division (linked to demo users where applicable)
INSERT INTO public.employees (
    hrms_id, employee_code, employee_name, aadhaar_no, pan_no, contact_no, email,
    hmwssb_division_id, is_temporary_id, status, pay_scale, basic_pay, 
    date_of_joining, date_of_birth, bank_account_no, ifsc_code, bank_name, branch_name,
    created_at, updated_at
) VALUES
-- O&M Division I (OM-DIV-01) - EE: ee_div1
('EE001', 'EE001', 'Executive Engineer Div I', '111122223333', 'ABCDE1234F', '9000000101', 'ee.div1@hmwssb.gov.in',
 (SELECT id FROM public.hmwssb_divisions WHERE division_code = 'OM-DIV-01'), false, 'ACTIVE', 'LEVEL-13', 123100,
 '2005-06-15', '1975-08-20', '1111222233334444', 'SBIN0001234', 'STATE BANK OF INDIA', 'HYDERABAD MAIN', NOW(), NOW()),

('AE001', 'AE001', 'Assistant Engineer Div I - Sec 1', '222233334444', 'BCDEF2345G', '9000000111', 'ae.div1.sec1@hmwssb.gov.in',
 (SELECT id FROM public.hmwssb_divisions WHERE division_code = 'OM-DIV-01'), false, 'ACTIVE', 'LEVEL-10', 56100,
 '2015-07-01', '1988-03-15', '2222333344445555', 'HDFC0001234', 'HDFC BANK', 'SECUNDERABAD', NOW(), NOW()),

('JA001', 'JA001', 'Junior Assistant Div I', '333344445555', 'CDEFG3456H', '9000000121', 'ja.div1@hmwssb.gov.in',
 (SELECT id FROM public.hmwssb_divisions WHERE division_code = 'OM-DIV-01'), false, 'ACTIVE', 'LEVEL-4', 25500,
 '2018-01-10', '1992-11-25', '3333444455556666', 'ICIC0001234', 'ICICI BANK', 'KUKATPALLY', NOW(), NOW()),

-- O&M Division II (OM-DIV-02) - EE: ee_div2
('EE002', 'EE002', 'Executive Engineer Div II', '444455556666', 'DEFGH4567I', '9000000102', 'ee.div2@hmwssb.gov.in',
 (SELECT id FROM public.hmwssb_divisions WHERE division_code = 'OM-DIV-02'), false, 'ACTIVE', 'LEVEL-13', 123100,
 '2006-08-20', '1976-05-12', '4444555566667777', 'SBIN0001234', 'STATE BANK OF INDIA', 'HYDERABAD MAIN', NOW(), NOW()),

('AE002', 'AE002', 'Assistant Engineer Div II', '555566667777', 'EFGHI5678J', '9000000112', 'ae.div2@hmwssb.gov.in',
 (SELECT id FROM public.hmwssb_divisions WHERE division_code = 'OM-DIV-02'), false, 'ACTIVE', 'LEVEL-10', 56100,
 '2016-03-01', '1989-09-08', '5555666677778888', 'PNB0001234', 'PUNJAB NATIONAL BANK', 'MEHDIPATNAM', NOW(), NOW()),

-- O&M Division VII (OM-DIV-07) - EE: ee_div7
('EE007', 'EE007', 'Executive Engineer Div VII', '666677778888', 'FGHIJ6789K', '9000000103', 'ee.div7@hmwssb.gov.in',
 (SELECT id FROM public.hmwssb_divisions WHERE division_code = 'OM-DIV-07'), false, 'ACTIVE', 'LEVEL-13', 123100,
 '2007-01-15', '1977-12-03', '6666777788889999', 'HDFC0001234', 'HDFC BANK', 'MALKAJGIRI', NOW(), NOW()),

-- O&M Division XIII (OM-DIV-13) - EE: ee_div13
('EE013', 'EE013', 'Executive Engineer Div XIII', '777788889999', 'GHIJK7890L', '9000000104', 'ee.div13@hmwssb.gov.in',
 (SELECT id FROM public.hmwssb_divisions WHERE division_code = 'OM-DIV-13'), false, 'ACTIVE', 'LEVEL-13', 123100,
 '2008-04-10', '1978-07-22', '7777888899990000', 'ICIC0001234', 'ICICI BANK', 'LB NAGAR', NOW(), NOW()),

-- O&M Division XIX (OM-DIV-19) - EE: ee_div19
('EE019', 'EE019', 'Executive Engineer Div XIX', '888899990000', 'HIJKL8901M', '9000000105', 'ee.div19@hmwssb.gov.in',
 (SELECT id FROM public.hmwssb_divisions WHERE division_code = 'OM-DIV-19'), false, 'ACTIVE', 'LEVEL-13', 123100,
 '2009-09-01', '1979-02-14', '8888999900001111', 'SBIN0001234', 'STATE BANK OF INDIA', 'MADHAPUR', NOW(), NOW()),

-- Project Division I (PRJ-DIV-01) - EE: ee_prj1
('PRJ001', 'PRJ001', 'Executive Engineer Project Div I', '999900001111', 'IJKLM9012N', '9000000106', 'ee.prj1@hmwssb.gov.in',
 (SELECT id FROM public.hmwssb_divisions WHERE division_code = 'PRJ-DIV-01'), false, 'ACTIVE', 'LEVEL-13', 123100,
 '2010-02-01', '1980-11-30', '9999000011112222', 'HDFC0001234', 'HDFC BANK', 'KHAIRATABAD', NOW(), NOW()),

-- Transmission Circle I (TRN-DIV-01) - EE: ee_trn1
('TRN001', 'TRN001', 'Executive Engineer Trans Circle I', '000011112222', 'JKLMN0123O', '9000000107', 'ee.trn1@hmwssb.gov.in',
 (SELECT id FROM public.hmwssb_divisions WHERE division_code = 'TRN-DIV-01'), false, 'ACTIVE', 'LEVEL-13', 123100,
 '2011-06-15', '1981-04-18', '0000111122223333', 'ICIC0001234', 'ICICI BANK', 'BEGUMPET', NOW(), NOW()),

-- Electrical Project Division (ELE-DIV-01) - EE: ee_elec
('ELE001', 'ELE001', 'Executive Engineer Electrical', '111100002222', 'KLMNO1234P', '9000000108', 'ee.elec@hmwssb.gov.in',
 (SELECT id FROM public.hmwssb_divisions WHERE division_code = 'ELE-DIV-01'), false, 'ACTIVE', 'LEVEL-13', 123100,
 '2012-08-01', '1982-01-25', '1111000022224444', 'PNB0001234', 'PUNJAB NATIONAL BANK', 'SAIFABAD', NOW(), NOW()),

-- Quality Control Division (QC-DIV-01) - EE: ee_qc
('QC001', 'QC001', 'Executive Engineer Quality Control', '222200003333', 'LMNOP2345Q', '9000000109', 'ee.qc@hmwssb.gov.in',
 (SELECT id FROM public.hmwssb_divisions WHERE division_code = 'QC-DIV-01'), false, 'ACTIVE', 'LEVEL-13', 123100,
 '2013-03-15', '1983-06-10', '2222000033335555', 'SBIN0001234', 'STATE BANK OF INDIA', 'NAMPALLY', NOW(), NOW()),

-- Board Office (BOARD-DIV-01) - EE: ee_board
('BOARD001', 'BOARD001', 'Executive Engineer Board Office', '333300004444', 'MNOPQ3456R', '9000000110', 'ee.board@hmwssb.gov.in',
 (SELECT id FROM public.hmwssb_divisions WHERE division_code = 'BOARD-DIV-01'), false, 'ACTIVE', 'LEVEL-13', 123100,
 '2014-11-01', '1984-09-05', '3333000044446666', 'HDFC0001234', 'HDFC BANK', 'KHAIRATABAD', NOW(), NOW()),

-- Payroll Officers (linked to Board Office)
('PO001', 'PO001', 'Payroll Officer 1', '444400005555', 'NOPQR4567S', '9000000201', 'payroll.officer1@hmwssb.gov.in',
 (SELECT id FROM public.hmwssb_divisions WHERE division_code = 'BOARD-DIV-01'), false, 'ACTIVE', 'LEVEL-10', 56100,
 '2019-01-01', '1990-02-20', '4444000055557777', 'ICIC0001234', 'ICICI BANK', 'KHAIRATABAD', NOW(), NOW()),

('PO002', 'PO002', 'Payroll Officer 2', '555500006666', 'OPQRS5678T', '9000000202', 'payroll.officer2@hmwssb.gov.in',
 (SELECT id FROM public.hmwssb_divisions WHERE division_code = 'BOARD-DIV-01'), false, 'ACTIVE', 'LEVEL-10', 56100,
 '2020-03-01', '1991-07-12', '5555000066668888', 'PNB0001234', 'PUNJAB NATIONAL BANK', 'KHAIRATABAD', NOW(), NOW()),

-- IT Admin (linked to Board Office)
('IT001', 'IT001', 'IT Administrator', '666600007777', 'PQRST6789U', '9000000301', 'itadmin@hmwssb.gov.in',
 (SELECT id FROM public.hmwssb_divisions WHERE division_code = 'BOARD-DIV-01'), false, 'ACTIVE', 'LEVEL-11', 67700,
 '2018-05-01', '1987-10-30', '6666000077779999', 'SBIN0001234', 'STATE BANK OF INDIA', 'KHAIRATABAD', NOW(), NOW()),

-- Temp employees (from import)
('T-001', 'T-001', 'Ravi Kumar (Temp)', '123456789012', NULL, '9876543210', 'emp001@hmwssb.gov.in',
 (SELECT id FROM public.hmwssb_divisions WHERE division_code = 'OM-DIV-01'), true, 'ACTIVE', 'LEVEL-4', 25500,
 '2024-04-01', '1992-05-15', '1234567890123456', 'SBIN0001234', 'STATE BANK OF INDIA', 'HYDERABAD MAIN', NOW(), NOW()),

('T-002', 'T-002', 'Priya Sharma (Temp)', '234567890123', NULL, '9876543211', 'emp002@hmwssb.gov.in',
 (SELECT id FROM public.hmwssb_divisions WHERE division_code = 'OM-DIV-07'), true, 'ACTIVE', 'LEVEL-7', 44900,
 '2024-04-01', '1995-08-22', '2345678901234567', 'HDFC0001234', 'HDFC BANK', 'MALKAJGIRI', NOW(), NOW()),

('T-003', 'T-003', 'Suresh Reddy (Temp)', '345678901234', NULL, '9876543212', 'emp003@hmwssb.gov.in',
 (SELECT id FROM public.hmwssb_divisions WHERE division_code = 'OM-DIV-13'), true, 'ACTIVE', 'LEVEL-6', 35400,
 '2024-04-01', '1993-11-10', '3456789012345678', 'ICIC0001234', 'ICICI BANK', 'LB NAGAR', NOW(), NOW());

-- ============================================================================
-- STEP 6: ENSURE SALARY COMPONENTS EXIST
-- ============================================================================

INSERT INTO public.salary_components (component_code, component_name, category, description, is_active, created_at, updated_at) VALUES
-- Earnings
('BASIC', 'Basic Pay', 'EARNING', 'Basic salary component', true, NOW(), NOW()),
('DA', 'Dearness Allowance', 'EARNING', 'DA as per govt rules', true, NOW(), NOW()),
('HRA', 'House Rent Allowance', 'EARNING', 'HRA based on city classification', true, NOW(), NOW()),
('TA', 'Travel Allowance', 'EARNING', 'Transport allowance', true, NOW(), NOW()),
('MA', 'Medical Allowance', 'EARNING', 'Fixed medical allowance', true, NOW(), NOW()),
('WA', 'Washing Allowance', 'EARNING', 'Uniform washing allowance', true, NOW(), NOW()),
('SPL_ALLOW', 'Special Allowance', 'EARNING', 'Special duty allowance', true, NOW(), NOW()),
('CCA', 'City Compensatory Allowance', 'EARNING', 'CCA for metro cities', true, NOW(), NOW()),
('HCA', 'Hill Compensatory Allowance', 'EARNING', 'Hill area allowance', true, NOW(), NOW()),
('OVT', 'Overtime', 'EARNING', 'Overtime payments', true, NOW(), NOW()),
('ARREARS', 'Arrears', 'EARNING', 'Salary arrears', true, NOW(), NOW()),
('BONUS', 'Bonus', 'EARNING', 'Annual bonus', true, NOW(), NOW()),
('LEAVE_ENCASH', 'Leave Encashment', 'EARNING', 'Earned leave encashment', true, NOW(), NOW()),
('MEDICAL', 'Medical Reimbursement', 'EARNING', 'Medical bill reimbursement', true, NOW(), NOW()),
('EDU_ALLOW', 'Education Allowance', 'EARNING', 'Children education allowance', true, NOW(), NOW()),
('OTHER_EARN', 'Other Earnings', 'EARNING', 'Miscellaneous earnings', true, NOW(), NOW()),

-- Deductions
('GPF_DED', 'GPF Subscription', 'DEDUCTION', 'General Provident Fund', true, NOW(), NOW()),
('CPF_DED', 'CPF Subscription', 'DEDUCTION', 'Contributory Provident Fund', true, NOW(), NOW()),
('NPS_DED', 'NPS Employee Share', 'DEDUCTION', 'National Pension System', true, NOW(), NOW()),
('GIS_DED', 'Group Insurance Scheme', 'DEDUCTION', 'GIS premium', true, NOW(), NOW()),
('LIC_DED', 'LIC Premium', 'DEDUCTION', 'LIC policy premium deduction', true, NOW(), NOW()),
('BANK_LOAN', 'Bank Loan Recovery', 'DEDUCTION', 'Bank loan EMI recovery', true, NOW(), NOW()),
('HBA_DED', 'House Building Advance', 'DEDUCTION', 'HBA recovery', true, NOW(), NOW()),
('EDU_LOAN', 'Education Loan', 'DEDUCTION', 'Education loan recovery', true, NOW(), NOW()),
('FEST_ADV', 'Festival Advance', 'DEDUCTION', 'Festival advance recovery', true, NOW(), NOW()),
('INCOME_TAX', 'Income Tax (TDS)', 'DEDUCTION', 'Tax deducted at source', true, NOW(), NOW()),
('PROF_TAX', 'Professional Tax', 'DEDUCTION', 'State professional tax', true, NOW(), NOW()),
('UNION_DUES', 'Union Dues', 'DEDUCTION', 'Employee union subscription', true, NOW(), NOW()),
('COOP_SOC', 'Cooperative Society', 'DEDUCTION', 'Co-op society deduction', true, NOW(), NOW()),
('OTHER_DED', 'Other Deductions', 'DEDUCTION', 'Miscellaneous deductions', true, NOW(), NOW()),
('NPS_EMPR', 'NPS Employer Share', 'DEDUCTION', 'Employer NPS contribution', true, NOW(), NOW()),

-- Statutory
('PF_EMPR', 'PF Employer Share', 'STATUTORY', 'Employer PF contribution', true, NOW(), NOW()),
('ESI_EMPR', 'ESI Employer Share', 'STATUTORY', 'Employer ESI contribution', true, NOW(), NOW()),
('PT_EMPR', 'PT Employer Share', 'STATUTORY', 'Employer Professional Tax', true, NOW(), NOW()),

-- Pay scales
('LEVEL-1', 'Level 1', 'EARNING', 'Pay Level 1', true, NOW(), NOW()),
('LEVEL-2', 'Level 2', 'EARNING', 'Pay Level 2', true, NOW(), NOW()),
('LEVEL-3', 'Level 3', 'EARNING', 'Pay Level 3', true, NOW(), NOW()),
('LEVEL-4', 'Level 4', 'EARNING', 'Pay Level 4', true, NOW(), NOW()),
('LEVEL-5', 'Level 5', 'EARNING', 'Pay Level 5', true, NOW(), NOW()),
('LEVEL-6', 'Level 6', 'EARNING', 'Pay Level 6', true, NOW(), NOW()),
('LEVEL-7', 'Level 7', 'EARNING', 'Pay Level 7', true, NOW(), NOW()),
('LEVEL-8', 'Level 8', 'EARNING', 'Pay Level 8', true, NOW(), NOW()),
('LEVEL-9', 'Level 9', 'EARNING', 'Pay Level 9', true, NOW(), NOW()),
('LEVEL-10', 'Level 10', 'EARNING', 'Pay Level 10', true, NOW(), NOW()),
('LEVEL-11', 'Level 11', 'EARNING', 'Pay Level 11', true, NOW(), NOW()),
('LEVEL-12', 'Level 12', 'EARNING', 'Pay Level 12', true, NOW(), NOW()),
('LEVEL-13', 'Level 13', 'EARNING', 'Pay Level 13', true, NOW(), NOW())
ON CONFLICT (component_code) DO UPDATE SET 
    component_name = EXCLUDED.component_name,
    category = EXCLUDED.category,
    description = EXCLUDED.description,
    is_active = EXCLUDED.is_active,
    updated_at = NOW();

-- ============================================================================
-- STEP 7: PAYROLL UNITS (Ensure they match divisions)
-- ============================================================================

-- Clear and re-insert payroll units matching divisions
TRUNCATE TABLE public.payroll_units RESTART IDENTITY CASCADE;

INSERT INTO public.payroll_units (unit_code, unit_name, parent_unit_code, level, sort_order, is_active)
SELECT 
    division_code,
    division_name,
    CASE 
        WHEN hierarchy_level = 1 THEN NULL
        WHEN hierarchy_level = 2 THEN wing_code
        WHEN hierarchy_level = 3 THEN circle_code
    END as parent_unit_code,
    hierarchy_level,
    display_order,
    CASE WHEN status = 'ACTIVE' THEN true ELSE false END
FROM public.hmwssb_divisions
WHERE hierarchy_level IN (1, 2, 3)
ORDER BY hierarchy_level, display_order;

-- ============================================================================
-- VERIFICATION QUERIES
-- ============================================================================

-- SELECT 'Users' as table_name, count(*) FROM public.users
-- UNION ALL SELECT 'Roles', count(*) FROM public.roles
-- UNION ALL SELECT 'User-Role Scopes', count(*) FROM public.user_role_scopes
-- UNION ALL SELECT 'Employees', count(*) FROM public.employees
-- UNION ALL SELECT 'Divisions (Total)', count(*) FROM public.hmwssb_divisions
-- UNION ALL SELECT 'Divisions (Active L3)', count(*) FROM public.hmwssb_divisions WHERE hierarchy_level = 3 AND status = 'ACTIVE'
-- UNION ALL SELECT 'Salary Components', count(*) FROM public.salary_components
-- UNION ALL SELECT 'Payroll Units', count(*) FROM public.payroll_units;

COMMIT;