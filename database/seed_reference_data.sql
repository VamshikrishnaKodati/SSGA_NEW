-- ============================================================================
-- seed_reference_data.sql - idempotent reference/seed data.
--
-- Sources: sections 2-4 of 02_security.sql, section 6 of
-- 11_employee_master_and_components.sql, sections 5-6 of
-- 12_organization_master.sql. Safe to re-run (ON CONFLICT DO NOTHING).
--
-- Requires: 01_schema.sql applied, then seed_bootstrap_admin.sql.
-- ============================================================================

BEGIN;

-- ---- bootstrap organization (required by payroll-unit seeds) ---------------
INSERT INTO organizations (name, code, status)
VALUES ('SSGA Organization', 'SSGA', 'ACTIVE')
ON CONFLICT (code) DO NOTHING;

-- ---- roles / permissions / role_permissions -------------------------------

INSERT INTO roles (code, name, description, max_scope, is_system)
VALUES
('SYSTEM_ADMIN', 'System Administrator',
 'Full system administration', 'ORG', true),

('ORG_ADMIN', 'Organization Administrator',
 'Organization-wide administration', 'ORG', true),

('WING_ADMIN', 'Wing Administrator',
 'Administration within assigned Wing', 'WING', true),

('DIVISION_ADMIN', 'Division Administrator',
 'Administration within assigned Division', 'DIVISION', true),

('PAYROLL_OPERATOR', 'Salary / Payroll Operator',
 'Salary processing within assigned scope', 'DIVISION', true),

('APPROVING_AUTHORITY', 'Approving Authority',
 'Review and approval of payroll', 'DIVISION', true),

('REPORT_USER', 'Report User',
 'View and download reports', 'DIVISION', true),

('EMPLOYEE_SELF', 'Employee Self',
 'Own employee information and statement', 'SELF', true),

('AUDITOR', 'Auditor',
 'Read-only audit access', 'ORG', true)

ON CONFLICT (code) DO NOTHING;

-- ============================================================
-- 3. PERMISSIONS
-- ============================================================

INSERT INTO permissions (code, module, description)
VALUES
('organization.view', 'ORGANIZATION', 'View organization'),
('organization.manage', 'ORGANIZATION', 'Manage organization'),

('wing.view', 'ORGANIZATION', 'View wings'),
('wing.manage', 'ORGANIZATION', 'Manage wings'),

('division.view', 'ORGANIZATION', 'View divisions'),
('division.manage', 'ORGANIZATION', 'Manage divisions'),

('employee.view', 'EMPLOYEE', 'View employees'),
('employee.create', 'EMPLOYEE', 'Create employees'),
('employee.update', 'EMPLOYEE', 'Update employees'),
('employee.hrms_replace', 'EMPLOYEE', 'Replace temporary HRMS'),

('salary.view', 'SALARY', 'View salary data'),
('salary.manage', 'SALARY', 'Manage salary master'),
('salary.prepare', 'SALARY', 'Prepare payroll'),

('payroll.view', 'PAYROLL', 'View payroll'),
('payroll.prepare', 'PAYROLL', 'Generate payroll'),
('payroll.verify', 'PAYROLL', 'Verify payroll'),
('payroll.submit', 'PAYROLL', 'Submit payroll'),
('payroll.approve', 'PAYROLL', 'Approve payroll'),
('payroll.finalize', 'PAYROLL', 'Finalize payroll'),
('payroll.reopen', 'PAYROLL', 'Reopen payroll'),
('payroll.period.manage', 'PAYROLL', 'Manage organization-wide payroll periods'),

('statement.view', 'STATEMENT', 'View salary statements'),
('statement.generate', 'STATEMENT', 'Generate salary statements'),
('statement.download', 'STATEMENT', 'Download salary statements'),

('report.view', 'REPORT', 'View reports'),
('export.download', 'REPORT', 'Download exports'),

('user.manage', 'SECURITY', 'Manage users'),
('scope.manage', 'SECURITY', 'Manage organizational scopes'),

('audit.view', 'AUDIT', 'View audit logs')

ON CONFLICT (code) DO NOTHING;

-- ============================================================
-- 4. ROLE → PERMISSION
-- ============================================================

-- System Administrator gets all permissions.
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'SYSTEM_ADMIN'
ON CONFLICT DO NOTHING;

-- Organization Administrator
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'organization.view','organization.manage',
    'wing.view','wing.manage',
    'division.view','division.manage',
    'employee.view','employee.create','employee.update',
    'employee.hrms_replace',
    'salary.view','salary.manage',
    'payroll.period.manage',
    'payroll.view','payroll.prepare','payroll.verify',
    'payroll.submit',
    'statement.view','statement.generate','statement.download',
    'report.view','export.download'
)
WHERE r.code = 'ORG_ADMIN'
ON CONFLICT DO NOTHING;

-- Wing Administrator
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'wing.view',
    'division.view',
    'employee.view',
    'employee.create',
    'employee.update',
    'employee.hrms_replace',
    'salary.view',
    'salary.manage',
    'payroll.view',
    'report.view',
    'export.download'
)
WHERE r.code = 'WING_ADMIN'
ON CONFLICT DO NOTHING;

-- Division Administrator
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'division.view',
    'employee.view',
    'employee.create',
    'employee.update',
    'employee.hrms_replace',
    'salary.view',
    'payroll.view',
    'report.view'
)
WHERE r.code = 'DIVISION_ADMIN'
ON CONFLICT DO NOTHING;

-- Payroll Operator
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'employee.view',
    'salary.view',
    'salary.prepare',
    'payroll.view',
    'payroll.prepare',
    'payroll.verify',
    'payroll.submit'
)
WHERE r.code = 'PAYROLL_OPERATOR'
ON CONFLICT DO NOTHING;

-- Approving Authority
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'employee.view',
    'salary.view',
    'payroll.view',
    'payroll.approve',
    'payroll.finalize'
)
WHERE r.code = 'APPROVING_AUTHORITY'
ON CONFLICT DO NOTHING;

-- Report User
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'employee.view',
    'salary.view',
    'payroll.view',
    'statement.view',
    'statement.download',
    'report.view',
    'export.download'
)
WHERE r.code = 'REPORT_USER'
ON CONFLICT DO NOTHING;

-- Employee Self
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'employee.view',
    'statement.view',
    'statement.download'
)
WHERE r.code = 'EMPLOYEE_SELF'
ON CONFLICT DO NOTHING;

-- Auditor
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN (
    'audit.view',
    'report.view'
)
WHERE r.code = 'AUDITOR'
ON CONFLICT DO NOTHING;

-- ---- salary components ----------------------------------------------------
INSERT INTO public.salary_components
    (component_code, component_name, category, calculation_type, status, display_order)
VALUES
    ('BASIC_PAY',               'Basic Pay',               'EARNING',   'FIXED', 'ACTIVE',  1),
    ('T_SPL_INCREMENT',         'T Spl Increment',         'EARNING',   'FIXED', 'ACTIVE',  2),
    ('FP_PP',                   'FP PP',                   'EARNING',   'FIXED', 'ACTIVE',  3),
    ('SPL_PAY_G_MITRA',         'Spl Pay G Mitra',         'EARNING',   'FIXED', 'ACTIVE',  4),
    ('DA_33_67',                'DA 33 67',                'EARNING',   'FIXED', 'ACTIVE',  5),
    ('HRA_24',                  'HRA 24',                  'EARNING',   'FIXED', 'ACTIVE',  6),
    ('ADDL_HRA',                'Addl HRA',                'EARNING',   'FIXED', 'ACTIVE',  7),
    ('CCA',                     'CCA',                     'EARNING',   'FIXED', 'ACTIVE',  8),
    ('PETROL_AUTO_ALLOW',       'Petrol Auto Allowance',   'EARNING',   'FIXED', 'ACTIVE',  9),
    ('WASHING_ALLOW',           'Washing Allowance',       'EARNING',   'FIXED', 'ACTIVE', 10),
    ('BOARD_FORMATION_DAY',     'Board Formation Day',     'EARNING',   'FIXED', 'ACTIVE', 11),
    ('IR_5_2023',               'IR 5 2023',               'EARNING',   'FIXED', 'ACTIVE', 12),
    ('DA_ARREARS_26_39',        'DA Arrears 26 39',        'EARNING',   'FIXED', 'ACTIVE', 13),
    ('DA_ARREARS_30_03',        'DA Arrears 30 03',        'EARNING',   'FIXED', 'ACTIVE', 14),
    ('DA_ARREARS_33_67',        'DA Arrears 33 67',        'EARNING',   'FIXED', 'ACTIVE', 15),
    ('GPF_EPF',                 'GPF/EPF',                 'DEDUCTION', 'FIXED', 'ACTIVE', 16),
    ('COURT_ATCH',              'Court Attachment',        'DEDUCTION', 'FIXED', 'ACTIVE', 17),
    ('PENSION_SCHEME',          'Pension Scheme',          'DEDUCTION', 'FIXED', 'ACTIVE', 18),
    ('TSGLIF',                  'TSGLIF',                  'DEDUCTION', 'FIXED', 'ACTIVE', 19),
    ('GIS',                     'GIS',                     'DEDUCTION', 'FIXED', 'ACTIVE', 20),
    ('PRC_2020_NPS',            'PRC 2020 NPS',            'DEDUCTION', 'FIXED', 'ACTIVE', 21),
    ('ELECT_CHAR',              'Electricity Charges',     'DEDUCTION', 'FIXED', 'ACTIVE', 22),
    ('FESTIVAL_ADV',            'Festival Advance',        'DEDUCTION', 'FIXED', 'ACTIVE', 23),
    ('EDCN_ADV',                'Education Advance',       'DEDUCTION', 'FIXED', 'ACTIVE', 24),
    ('VEHICLE_ADV',             'Vehicle Advance',         'DEDUCTION', 'FIXED', 'ACTIVE', 25),
    ('MOPED_ADV',               'Moped Advance',           'DEDUCTION', 'FIXED', 'ACTIVE', 26),
    ('EXCESS_SALARY_RECOVERY',  'Excess Salary Recovery',  'DEDUCTION', 'FIXED', 'ACTIVE', 27),
    ('EPF_12_PERCENT',          'EPF 12 Percent',          'DEDUCTION', 'FIXED', 'ACTIVE', 28),
    ('SBI_LIFE_INSURANCE',      'SBI Life Insurance',      'DEDUCTION', 'FIXED', 'ACTIVE', 29),
    ('ONE_DAY_SALARY',          'One Day Salary',          'DEDUCTION', 'FIXED', 'ACTIVE', 30),
    ('MARRIAGE_LOAN',           'Marriage Loan',           'DEDUCTION', 'FIXED', 'ACTIVE', 31),
    ('MGB_AN',                  'MGB AN',                  'DEDUCTION', 'FIXED', 'ACTIVE', 32),
    ('PROF_TAX',                'Professional Tax',        'DEDUCTION', 'FIXED', 'ACTIVE', 33),
    ('INCOME_TAX',              'Income Tax',              'DEDUCTION', 'FIXED', 'ACTIVE', 34),
    ('EPF_PRC_ARREARS',         'EPF PRC Arrears',         'DEDUCTION', 'FIXED', 'ACTIVE', 35),
    ('PRC_EXCESS_RECOVERY',     'PRC Excess Recovery',     'DEDUCTION', 'FIXED', 'ACTIVE', 36),
    ('MGB_PC',                  'MGB PC',                  'DEDUCTION', 'FIXED', 'ACTIVE', 37),
    ('TELANGANA_HARITHA_NIDHI', 'Telangana Haritha Nidhi', 'DEDUCTION', 'FIXED', 'ACTIVE', 38),
    ('CCA_RECOVERY_DARUSSALAM', 'CCA Recovery Darussalam', 'DEDUCTION', 'FIXED', 'ACTIVE', 39),
    ('SBH_ANAND_NAGAR',         'SBH Anand Nagar',         'DEDUCTION', 'FIXED', 'ACTIVE', 40),
    ('FLAG_DAY_2025',           'Flag Day 2025',           'DEDUCTION', 'FIXED', 'ACTIVE', 41),
    ('LIC',                     'LIC',                     'DEDUCTION', 'FIXED', 'ACTIVE', 42)
ON CONFLICT (component_code) DO NOTHING;


-- ---- parent groups --------------------------------------------------------
INSERT INTO public.parent_groups (organization_id, name, code, status)
SELECT o.id, v.name, v.code, 'ACTIVE'
FROM (SELECT id FROM public.organizations ORDER BY id LIMIT 1) o
CROSS JOIN (VALUES
    ('Operations & Maintenance', 'OM'),
    ('Projects',                 'PROJECTS'),
    ('Transmission',             'TRANSMISSION'),
    ('STP & Electrical Services','STP_ELECTRICAL'),
    ('Quality & Stores',         'QUALITY_STORES'),
    ('Board Administration',     'BOARD_ADMIN')
) AS v(name, code)
ON CONFLICT (organization_id, code) DO NOTHING;

-- ---------------------------------------------------------------------------
-- 6. Seed proposed payroll units.
--
--    Counts by source category: O&M Divisions I-XXIV (24), O&M Circles I-V (5),
--    Project Divisions I-VIII (8), PCC-I/II (2), Transmission Circle + I-IV (5),
--    STP Circles I-II (2), Electrical Project Division (1), Quality Control &
--    Vigilance Division (1), Central Stores Division (1), Board Office (1)
--    => 50 active units, plus PCC-III seeded INACTIVE (disputed / crossed out).
--
--    PENDING_VERIFICATION units: O&M XIII, XXII, XXIII, O&M Circle V,
--    Electrical Project Division (name discrepancy), and PCC-III.
-- ---------------------------------------------------------------------------
INSERT INTO public.payroll_units
    (parent_group_id, code, name, unit_type, status,
     verification_status, verification_note, display_order)
SELECT pg.id, v.code, v.name, v.unit_type, v.status,
       v.verification_status, v.verification_note, v.display_order
FROM (VALUES
    -- Operations & Maintenance: O&M Divisions I - XXIV
    ('OM',            'OM-DIV-01', 'O&M Division I',    'DIVISION', 'ACTIVE',   'CONFIRMED',            NULL::text, 1),
    ('OM',            'OM-DIV-02', 'O&M Division II',   'DIVISION', 'ACTIVE',   'CONFIRMED',            NULL, 2),
    ('OM',            'OM-DIV-03', 'O&M Division III',  'DIVISION', 'ACTIVE',   'CONFIRMED',            NULL, 3),
    ('OM',            'OM-DIV-04', 'O&M Division IV',   'DIVISION', 'ACTIVE',   'CONFIRMED',            NULL, 4),
    ('OM',            'OM-DIV-05', 'O&M Division V',    'DIVISION', 'ACTIVE',   'CONFIRMED',            NULL, 5),
    ('OM',            'OM-DIV-06', 'O&M Division VI',   'DIVISION', 'ACTIVE',   'CONFIRMED',            NULL, 6),
    ('OM',            'OM-DIV-07', 'O&M Division VII',  'DIVISION', 'ACTIVE',   'CONFIRMED',            NULL, 7),
    ('OM',            'OM-DIV-08', 'O&M Division VIII', 'DIVISION', 'ACTIVE',   'CONFIRMED',            NULL, 8),
    ('OM',            'OM-DIV-09', 'O&M Division IX',   'DIVISION', 'ACTIVE',   'CONFIRMED',            NULL, 9),
    ('OM',            'OM-DIV-10', 'O&M Division X',    'DIVISION', 'ACTIVE',   'CONFIRMED',            NULL, 10),
    ('OM',            'OM-DIV-11', 'O&M Division XI',   'DIVISION', 'ACTIVE',   'CONFIRMED',            NULL, 11),
    ('OM',            'OM-DIV-12', 'O&M Division XII',  'DIVISION', 'ACTIVE',   'CONFIRMED',            NULL, 12),
    ('OM',            'OM-DIV-13', 'O&M Division XIII', 'DIVISION', 'ACTIVE',   'PENDING_VERIFICATION', 'O&M Division XIII carries an unreadable mark in the source register. Seeded active but flagged; not removed. Awaiting authorized confirmation.', 13),
    ('OM',            'OM-DIV-14', 'O&M Division XIV',  'DIVISION', 'ACTIVE',   'CONFIRMED',            NULL, 14),
    ('OM',            'OM-DIV-15', 'O&M Division XV',   'DIVISION', 'ACTIVE',   'CONFIRMED',            NULL, 15),
    ('OM',            'OM-DIV-16', 'O&M Division XVI',  'DIVISION', 'ACTIVE',   'CONFIRMED',            NULL, 16),
    ('OM',            'OM-DIV-17', 'O&M Division XVII', 'DIVISION', 'ACTIVE',   'CONFIRMED',            NULL, 17),
    ('OM',            'OM-DIV-18', 'O&M Division XVIII','DIVISION', 'ACTIVE',   'CONFIRMED',            NULL, 18),
    ('OM',            'OM-DIV-19', 'O&M Division XIX',  'DIVISION', 'ACTIVE',   'CONFIRMED',            NULL, 19),
    ('OM',            'OM-DIV-20', 'O&M Division XX',   'DIVISION', 'ACTIVE',   'CONFIRMED',            NULL, 20),
    ('OM',            'OM-DIV-21', 'O&M Division XXI',  'DIVISION', 'ACTIVE',   'CONFIRMED',            NULL, 21),
    ('OM',            'OM-DIV-22', 'O&M Division XXII', 'DIVISION', 'ACTIVE',   'PENDING_VERIFICATION', 'O&M Division XXII carries a mark in the source register. Seeded active but flagged; not removed. Awaiting authorized confirmation.', 22),
    ('OM',            'OM-DIV-23', 'O&M Division XXIII','DIVISION', 'ACTIVE',   'PENDING_VERIFICATION', 'O&M Division XXIII carries a mark in the source register. Seeded active but flagged; not removed. Awaiting authorized confirmation.', 23),
    ('OM',            'OM-DIV-24', 'O&M Division XXIV', 'DIVISION', 'ACTIVE',   'CONFIRMED',            NULL, 24),
    -- Operations & Maintenance: O&M Circles I - V
    ('OM',            'OM-CIR-01', 'O&M Circle I',      'CIRCLE',   'ACTIVE',   'CONFIRMED',            NULL, 25),
    ('OM',            'OM-CIR-02', 'O&M Circle II',     'CIRCLE',   'ACTIVE',   'CONFIRMED',            NULL, 26),
    ('OM',            'OM-CIR-03', 'O&M Circle III',    'CIRCLE',   'ACTIVE',   'CONFIRMED',            NULL, 27),
    ('OM',            'OM-CIR-04', 'O&M Circle IV',     'CIRCLE',   'ACTIVE',   'CONFIRMED',            NULL, 28),
    ('OM',            'OM-CIR-05', 'O&M Circle V',      'CIRCLE',   'ACTIVE',   'PENDING_VERIFICATION', 'O&M Circle V appears only in the directory listing, not the unit register. Seeded active but flagged for verification.', 29),
    -- Projects: Project Divisions I - VIII, PCC-I / PCC-II
    ('PROJECTS',      'PRJ-DIV-01', 'Project Division I',    'PROJECT_DIVISION', 'ACTIVE', 'CONFIRMED', NULL, 30),
    ('PROJECTS',      'PRJ-DIV-02', 'Project Division II',   'PROJECT_DIVISION', 'ACTIVE', 'CONFIRMED', NULL, 31),
    ('PROJECTS',      'PRJ-DIV-03', 'Project Division III',  'PROJECT_DIVISION', 'ACTIVE', 'CONFIRMED', NULL, 32),
    ('PROJECTS',      'PRJ-DIV-04', 'Project Division IV',   'PROJECT_DIVISION', 'ACTIVE', 'CONFIRMED', NULL, 33),
    ('PROJECTS',      'PRJ-DIV-05', 'Project Division V',    'PROJECT_DIVISION', 'ACTIVE', 'CONFIRMED', NULL, 34),
    ('PROJECTS',      'PRJ-DIV-06', 'Project Division VI',   'PROJECT_DIVISION', 'ACTIVE', 'CONFIRMED', NULL, 35),
    ('PROJECTS',      'PRJ-DIV-07', 'Project Division VII',  'PROJECT_DIVISION', 'ACTIVE', 'CONFIRMED', NULL, 36),
    ('PROJECTS',      'PRJ-DIV-08', 'Project Division VIII', 'PROJECT_DIVISION', 'ACTIVE', 'CONFIRMED', NULL, 37),
    ('PROJECTS',      'PCC-01',     'PCC-I',  'PCC', 'ACTIVE', 'CONFIRMED', NULL, 38),
    ('PROJECTS',      'PCC-02',     'PCC-II', 'PCC', 'ACTIVE', 'CONFIRMED', NULL, 39),
    ('PROJECTS',      'PCC-03',     'PCC-III', 'PCC', 'INACTIVE', 'PENDING_VERIFICATION', 'PCC-III is crossed out in the source register. Seeded INACTIVE (not activated) and flagged; never deleted. Awaiting authorized decision.', 40),
    -- Transmission: Transmission Circle + Transmission Circles I - IV
    ('TRANSMISSION',  'TRN-CIR-00', 'Transmission Circle',   'TRANSMISSION_CIRCLE', 'ACTIVE', 'CONFIRMED', NULL, 41),
    ('TRANSMISSION',  'TRN-CIR-01', 'Transmission Circle I', 'TRANSMISSION_CIRCLE', 'ACTIVE', 'CONFIRMED', NULL, 42),
    ('TRANSMISSION',  'TRN-CIR-02', 'Transmission Circle II','TRANSMISSION_CIRCLE', 'ACTIVE', 'CONFIRMED', NULL, 43),
    ('TRANSMISSION',  'TRN-CIR-03', 'Transmission Circle III','TRANSMISSION_CIRCLE','ACTIVE', 'CONFIRMED', NULL, 44),
    ('TRANSMISSION',  'TRN-CIR-04', 'Transmission Circle IV','TRANSMISSION_CIRCLE', 'ACTIVE', 'CONFIRMED', NULL, 45),
    -- STP & Electrical Services: STP Circles I - II, Electrical Project Division
    ('STP_ELECTRICAL','STP-CIR-01', 'STP Circle I', 'STP_CIRCLE', 'ACTIVE', 'CONFIRMED', NULL, 46),
    ('STP_ELECTRICAL','STP-CIR-02', 'STP Circle II','STP_CIRCLE', 'ACTIVE', 'CONFIRMED', NULL, 47),
    ('STP_ELECTRICAL','ELE-PRJ-DIV','Electrical Project Division', 'ELECTRICAL_DIVISION', 'ACTIVE', 'PENDING_VERIFICATION', 'Source register name "Electrical Project Division"; official website lists "Electrical Division". Source name preserved; flagged for authorized review.', 48),
    -- Quality & Stores
    ('QUALITY_STORES','QC-VIG-DIV', 'Quality Control & Vigilance Division', 'QUALITY_VIGILANCE', 'ACTIVE', 'CONFIRMED', NULL, 49),
    ('QUALITY_STORES','CEN-STR-DIV','Central Stores Division', 'CENTRAL_STORES', 'ACTIVE', 'CONFIRMED', NULL, 50),
    -- Board Administration
    ('BOARD_ADMIN',   'BOARD-OFFICE','Board Office', 'BOARD_OFFICE', 'ACTIVE', 'CONFIRMED', NULL, 51)
) AS v(group_code, code, name, unit_type, status,
       verification_status, verification_note, display_order)
JOIN public.parent_groups pg
    ON pg.organization_id = (SELECT id FROM public.organizations ORDER BY id LIMIT 1)
   AND pg.code = v.group_code
ON CONFLICT (code) DO NOTHING;


COMMIT;
