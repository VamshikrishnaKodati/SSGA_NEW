-- ============================================================================
-- 16_seed_hmwssb_divisions.sql - HMWSSB 48 Administrative Divisions Seed Data
-- Run after 15_hmwssb_schema.sql
-- ============================================================================

BEGIN;

-- ---------------------------------------------------------------------------
-- Operations & Maintenance: O&M Divisions I - XXIV (24)
-- ---------------------------------------------------------------------------
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, parent_group, display_order, status, verification_status)
VALUES
('OM-DIV-01',  'O&M Division I',      'O&M Division',    'OM', 1,  'ACTIVE', 'CONFIRMED'),
('OM-DIV-02',  'O&M Division II',     'O&M Division',    'OM', 2,  'ACTIVE', 'CONFIRMED'),
('OM-DIV-03',  'O&M Division III',    'O&M Division',    'OM', 3,  'ACTIVE', 'CONFIRMED'),
('OM-DIV-04',  'O&M Division IV',     'O&M Division',    'OM', 4,  'ACTIVE', 'CONFIRMED'),
('OM-DIV-05',  'O&M Division V',      'O&M Division',    'OM', 5,  'ACTIVE', 'CONFIRMED'),
('OM-DIV-06',  'O&M Division VI',     'O&M Division',    'OM', 6,  'ACTIVE', 'CONFIRMED'),
('OM-DIV-07',  'O&M Division VII',    'O&M Division',    'OM', 7,  'ACTIVE', 'CONFIRMED'),
('OM-DIV-08',  'O&M Division VIII',   'O&M Division',    'OM', 8,  'ACTIVE', 'CONFIRMED'),
('OM-DIV-09',  'O&M Division IX',     'O&M Division',    'OM', 9,  'ACTIVE', 'CONFIRMED'),
('OM-DIV-10',  'O&M Division X',      'O&M Division',    'OM', 10, 'ACTIVE', 'CONFIRMED'),
('OM-DIV-11',  'O&M Division XI',     'O&M Division',    'OM', 11, 'ACTIVE', 'CONFIRMED'),
('OM-DIV-12',  'O&M Division XII',    'O&M Division',    'OM', 12, 'ACTIVE', 'CONFIRMED'),
('OM-DIV-13',  'O&M Division XIII',   'O&M Division',    'OM', 13, 'ACTIVE', 'PENDING_VERIFICATION'),
('OM-DIV-14',  'O&M Division XIV',    'O&M Division',    'OM', 14, 'ACTIVE', 'CONFIRMED'),
('OM-DIV-15',  'O&M Division XV',     'O&M Division',    'OM', 15, 'ACTIVE', 'CONFIRMED'),
('OM-DIV-16',  'O&M Division XVI',    'O&M Division',    'OM', 16, 'ACTIVE', 'CONFIRMED'),
('OM-DIV-17',  'O&M Division XVII',   'O&M Division',    'OM', 17, 'ACTIVE', 'CONFIRMED'),
('OM-DIV-18',  'O&M Division XVIII',  'O&M Division',    'OM', 18, 'ACTIVE', 'CONFIRMED'),
('OM-DIV-19',  'O&M Division XIX',    'O&M Division',    'OM', 19, 'ACTIVE', 'CONFIRMED'),
('OM-DIV-20',  'O&M Division XX',     'O&M Division',    'OM', 20, 'ACTIVE', 'CONFIRMED'),
('OM-DIV-21',  'O&M Division XXI',    'O&M Division',    'OM', 21, 'ACTIVE', 'CONFIRMED'),
('OM-DIV-22',  'O&M Division XXII',   'O&M Division',    'OM', 22, 'ACTIVE', 'PENDING_VERIFICATION'),
('OM-DIV-23',  'O&M Division XXIII',  'O&M Division',    'OM', 23, 'ACTIVE', 'PENDING_VERIFICATION'),
('OM-DIV-24',  'O&M Division XXIV',   'O&M Division',    'OM', 24, 'ACTIVE', 'CONFIRMED')

ON CONFLICT (division_code) DO UPDATE SET
    division_name = EXCLUDED.division_name,
    category = EXCLUDED.category,
    parent_group = EXCLUDED.parent_group,
    display_order = EXCLUDED.display_order,
    status = EXCLUDED.status,
    verification_status = EXCLUDED.verification_status,
    updated_at = NOW();

-- ---------------------------------------------------------------------------
-- Operations & Maintenance: O&M Circles I - V (5)
-- ---------------------------------------------------------------------------
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, parent_group, display_order, status, verification_status)
VALUES
('OM-CIR-01',  'O&M Circle I',   'O&M Circle',    'OM', 25, 'ACTIVE', 'CONFIRMED'),
('OM-CIR-02',  'O&M Circle II',  'O&M Circle',    'OM', 26, 'ACTIVE', 'CONFIRMED'),
('OM-CIR-03',  'O&M Circle III', 'O&M Circle',    'OM', 27, 'ACTIVE', 'CONFIRMED'),
('OM-CIR-04',  'O&M Circle IV',  'O&M Circle',    'OM', 28, 'ACTIVE', 'CONFIRMED'),
('OM-CIR-05',  'O&M Circle V',   'O&M Circle',    'OM', 29, 'ACTIVE', 'PENDING_VERIFICATION')

ON CONFLICT (division_code) DO UPDATE SET
    division_name = EXCLUDED.division_name,
    category = EXCLUDED.category,
    parent_group = EXCLUDED.parent_group,
    display_order = EXCLUDED.display_order,
    status = EXCLUDED.status,
    verification_status = EXCLUDED.verification_status,
    updated_at = NOW();

-- ---------------------------------------------------------------------------
-- Projects: Project Divisions I - VIII (8)
-- ---------------------------------------------------------------------------
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, parent_group, display_order, status, verification_status)
VALUES
('PRJ-DIV-01', 'Project Division I',   'Project Division', 'PROJECTS', 30, 'ACTIVE', 'CONFIRMED'),
('PRJ-DIV-02', 'Project Division II',  'Project Division', 'PROJECTS', 31, 'ACTIVE', 'CONFIRMED'),
('PRJ-DIV-03', 'Project Division III', 'Project Division', 'PROJECTS', 32, 'ACTIVE', 'CONFIRMED'),
('PRJ-DIV-04', 'Project Division IV',  'Project Division', 'PROJECTS', 33, 'ACTIVE', 'CONFIRMED'),
('PRJ-DIV-05', 'Project Division V',   'Project Division', 'PROJECTS', 34, 'ACTIVE', 'CONFIRMED'),
('PRJ-DIV-06', 'Project Division VI',  'Project Division', 'PROJECTS', 35, 'ACTIVE', 'CONFIRMED'),
('PRJ-DIV-07', 'Project Division VII', 'Project Division', 'PROJECTS', 36, 'ACTIVE', 'CONFIRMED'),
('PRJ-DIV-08', 'Project Division VIII','Project Division', 'PROJECTS', 37, 'ACTIVE', 'CONFIRMED')

ON CONFLICT (division_code) DO UPDATE SET
    division_name = EXCLUDED.division_name,
    category = EXCLUDED.category,
    parent_group = EXCLUDED.parent_group,
    display_order = EXCLUDED.display_order,
    status = EXCLUDED.status,
    verification_status = EXCLUDED.verification_status,
    updated_at = NOW();

-- ---------------------------------------------------------------------------
-- Projects: PCC-I, PCC-II (2) [PCC-III is inactive/disputed]
-- ---------------------------------------------------------------------------
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, parent_group, display_order, status, verification_status)
VALUES
('PCC-01', 'PCC-I',   'PCC', 'PROJECTS', 38, 'ACTIVE', 'CONFIRMED'),
('PCC-02', 'PCC-II',  'PCC', 'PROJECTS', 39, 'ACTIVE', 'CONFIRMED'),
('PCC-03', 'PCC-III', 'PCC', 'PROJECTS', 40, 'INACTIVE', 'PENDING_VERIFICATION')

ON CONFLICT (division_code) DO UPDATE SET
    division_name = EXCLUDED.division_name,
    category = EXCLUDED.category,
    parent_group = EXCLUDED.parent_group,
    display_order = EXCLUDED.display_order,
    status = EXCLUDED.status,
    verification_status = EXCLUDED.verification_status,
    updated_at = NOW();

-- ---------------------------------------------------------------------------
-- Transmission: Transmission Circle + Transmission Circles I - IV (5)
-- ---------------------------------------------------------------------------
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, parent_group, display_order, status, verification_status)
VALUES
('TRN-CIR-00', 'Transmission Circle',    'Transmission Circle', 'TRANSMISSION', 41, 'ACTIVE', 'CONFIRMED'),
('TRN-CIR-01', 'Transmission Circle I',  'Transmission Circle', 'TRANSMISSION', 42, 'ACTIVE', 'CONFIRMED'),
('TRN-CIR-02', 'Transmission Circle II', 'Transmission Circle', 'TRANSMISSION', 43, 'ACTIVE', 'CONFIRMED'),
('TRN-CIR-03', 'Transmission Circle III','Transmission Circle', 'TRANSMISSION', 44, 'ACTIVE', 'CONFIRMED'),
('TRN-CIR-04', 'Transmission Circle IV', 'Transmission Circle', 'TRANSMISSION', 45, 'ACTIVE', 'CONFIRMED')

ON CONFLICT (division_code) DO UPDATE SET
    division_name = EXCLUDED.division_name,
    category = EXCLUDED.category,
    parent_group = EXCLUDED.parent_group,
    display_order = EXCLUDED.display_order,
    status = EXCLUDED.status,
    verification_status = EXCLUDED.verification_status,
    updated_at = NOW();

-- ---------------------------------------------------------------------------
-- STP & Electrical Services: STP Circles I - II (2)
-- ---------------------------------------------------------------------------
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, parent_group, display_order, status, verification_status)
VALUES
('STP-CIR-01', 'STP Circle I',  'STP Circle', 'STP_ELECTRICAL', 46, 'ACTIVE', 'CONFIRMED'),
('STP-CIR-02', 'STP Circle II', 'STP Circle', 'STP_ELECTRICAL', 47, 'ACTIVE', 'CONFIRMED')

ON CONFLICT (division_code) DO UPDATE SET
    division_name = EXCLUDED.division_name,
    category = EXCLUDED.category,
    parent_group = EXCLUDED.parent_group,
    display_order = EXCLUDED.display_order,
    status = EXCLUDED.status,
    verification_status = EXCLUDED.verification_status,
    updated_at = NOW();

-- ---------------------------------------------------------------------------
-- Electrical Project Division (1)
-- ---------------------------------------------------------------------------
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, parent_group, display_order, status, verification_status)
VALUES
('ELE-PRJ-DIV', 'Electrical Project Division', 'Electrical Division', 'STP_ELECTRICAL', 48, 'ACTIVE', 'PENDING_VERIFICATION')

ON CONFLICT (division_code) DO UPDATE SET
    division_name = EXCLUDED.division_name,
    category = EXCLUDED.category,
    parent_group = EXCLUDED.parent_group,
    display_order = EXCLUDED.display_order,
    status = EXCLUDED.status,
    verification_status = EXCLUDED.verification_status,
    updated_at = NOW();

-- ---------------------------------------------------------------------------
-- Quality & Stores: Quality Control & Vigilance Division (1)
-- ---------------------------------------------------------------------------
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, parent_group, display_order, status, verification_status)
VALUES
('QC-VIG-DIV', 'Quality Control & Vigilance Division', 'Quality Vigilance', 'QUALITY_STORES', 49, 'ACTIVE', 'CONFIRMED')

ON CONFLICT (division_code) DO UPDATE SET
    division_name = EXCLUDED.division_name,
    category = EXCLUDED.category,
    parent_group = EXCLUDED.parent_group,
    display_order = EXCLUDED.display_order,
    status = EXCLUDED.status,
    verification_status = EXCLUDED.verification_status,
    updated_at = NOW();

-- ---------------------------------------------------------------------------
-- Quality & Stores: Central Stores Division (1)
-- ---------------------------------------------------------------------------
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, parent_group, display_order, status, verification_status)
VALUES
('CEN-STR-DIV', 'Central Stores Division', 'Central Stores', 'QUALITY_STORES', 50, 'ACTIVE', 'CONFIRMED')

ON CONFLICT (division_code) DO UPDATE SET
    division_name = EXCLUDED.division_name,
    category = EXCLUDED.category,
    parent_group = EXCLUDED.parent_group,
    display_order = EXCLUDED.display_order,
    status = EXCLUDED.status,
    verification_status = EXCLUDED.verification_status,
    updated_at = NOW();

-- ---------------------------------------------------------------------------
-- Board Administration: Board Office (1)
-- ---------------------------------------------------------------------------
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, parent_group, display_order, status, verification_status)
VALUES
('BOARD-OFFICE', 'Board Office', 'Board Office', 'BOARD_ADMIN', 51, 'ACTIVE', 'CONFIRMED')

ON CONFLICT (division_code) DO UPDATE SET
    division_name = EXCLUDED.division_name,
    category = EXCLUDED.category,
    parent_group = EXCLUDED.parent_group,
    display_order = EXCLUDED.display_order,
    status = EXCLUDED.status,
    verification_status = EXCLUDED.verification_status,
    updated_at = NOW();

-- ---------------------------------------------------------------------------
-- Verification: Total should be 48 active + 1 inactive (PCC-III) = 49 records
-- ---------------------------------------------------------------------------
-- SELECT count(*), status FROM public.hmwssb_divisions GROUP BY status ORDER BY status;
-- Expected: 48 ACTIVE, 1 INACTIVE

COMMIT;