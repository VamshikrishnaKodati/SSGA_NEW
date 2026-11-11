-- ============================================================================
-- 18_add_division_hierarchy.sql - Add Wing/Circle/Division Hierarchy Support
-- Run after 15_hmwssb_schema.sql and 16_seed_hmwssb_divisions.sql
-- ============================================================================

BEGIN;

-- ---------------------------------------------------------------------------
-- Add hierarchy columns to hmwssb_divisions
-- ---------------------------------------------------------------------------
ALTER TABLE public.hmwssb_divisions
    ADD COLUMN IF NOT EXISTS wing_code VARCHAR(50),                    -- 'OM', 'PROJECTS', 'TRANSMISSION', 'STP_ELECTRICAL', 'QUALITY_STORES', 'BOARD_ADMIN'
    ADD COLUMN IF NOT EXISTS wing_name VARCHAR(150),                   -- 'Operations & Maintenance Wing', 'Project Wing', etc.
    ADD COLUMN IF NOT EXISTS circle_code VARCHAR(50),                  -- 'OM-CIR-I', 'PCC-I', 'TRN-CIR', etc.
    ADD COLUMN IF NOT EXISTS circle_name VARCHAR(150),                 -- 'O&M Circle I', 'PCC-I', 'Transmission Circle'
    ADD COLUMN IF NOT EXISTS parent_division_id BIGINT REFERENCES public.hmwssb_divisions(id),
    ADD COLUMN IF NOT EXISTS hierarchy_level SMALLINT DEFAULT 3,       -- 1=Wing, 2=Circle, 3=Division
    ADD COLUMN IF NOT EXISTS area_coverage VARCHAR(200),               -- Geographic area covered
    ADD COLUMN IF NOT EXISTS head_designation VARCHAR(100);            -- 'Executive Engineer', 'Superintending Engineer', 'Chief Engineer'

-- Indexes for hierarchy queries
CREATE INDEX IF NOT EXISTS ix_hmwssb_divisions_wing ON public.hmwssb_divisions(wing_code);
CREATE INDEX IF NOT EXISTS ix_hmwssb_divisions_circle ON public.hmwssb_divisions(circle_code);
CREATE INDEX IF NOT EXISTS ix_hmwssb_divisions_parent ON public.hmwssb_divisions(parent_division_id);
CREATE INDEX IF NOT EXISTS ix_hmwssb_divisions_level ON public.hmwssb_divisions(hierarchy_level);

-- ---------------------------------------------------------------------------
-- Helper function to get division ID by code
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION public.get_division_id(p_code VARCHAR)
RETURNS BIGINT
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = public, pg_catalog
AS $$
DECLARE
    v_id BIGINT;
BEGIN
    SELECT id INTO v_id FROM public.hmwssb_divisions WHERE division_code = p_code LIMIT 1;
    RETURN v_id;
END;
$$;

GRANT EXECUTE ON FUNCTION public.get_division_id(VARCHAR) TO ssga_app;

-- ---------------------------------------------------------------------------
-- Clear existing seed data and re-insert with proper hierarchy
-- ---------------------------------------------------------------------------
TRUNCATE TABLE public.hmwssb_divisions RESTART IDENTITY CASCADE;

-- ============================================================================
-- WING LEVEL (Level 1) - 6 Wings
-- ============================================================================
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, wing_code, wing_name, hierarchy_level, parent_group, display_order, status, verification_status, head_designation)
VALUES
('WING-OM',    'Operations & Maintenance Wing',     'Wing', 'OM',            'Operations & Maintenance Wing',     1, 'OM',            1,  'ACTIVE', 'CONFIRMED', 'Chief Engineer (O&M)'),
('WING-PRJ',   'Project Wing',                      'Wing', 'PROJECTS',      'Project Wing',                      1, 'PROJECTS',      2,  'ACTIVE', 'CONFIRMED', 'Chief Engineer (Projects)'),
('WING-TRN',   'Transmission Wing',                 'Wing', 'TRANSMISSION',  'Transmission Wing',                 1, 'TRANSMISSION',  3,  'ACTIVE', 'CONFIRMED', 'Chief Engineer (Transmission)'),
('WING-SE',    'Special Engineering Wing',          'Wing', 'STP_ELECTRICAL','Special Engineering Wing',          1, 'STP_ELECTRICAL',4,  'ACTIVE', 'CONFIRMED', 'Chief Engineer (Special Engg)'),
('WING-QS',    'Quality & Stores Wing',             'Wing', 'QUALITY_STORES','Quality & Stores Wing',             1, 'QUALITY_STORES',5,  'ACTIVE', 'CONFIRMED', 'Chief Engineer (Quality)'),
('WING-BA',    'Board Administration Wing',         'Wing', 'BOARD_ADMIN',   'Board Administration Wing',         1, 'BOARD_ADMIN',   6,  'ACTIVE', 'CONFIRMED', 'Executive Director');

-- ============================================================================
-- CIRCLE LEVEL (Level 2) - Circles under each Wing
-- ============================================================================
-- O&M Wing Circles
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, wing_code, wing_name, circle_code, circle_name, hierarchy_level, parent_group, parent_division_id, display_order, status, verification_status, head_designation)
VALUES
('OM-CIR-I',   'O&M Circle I',     'Circle', 'OM', 'Operations & Maintenance Wing', 'OM-CIR-I', 'O&M Circle I',     2, 'OM', (SELECT id FROM public.hmwssb_divisions WHERE division_code='WING-OM'), 10, 'ACTIVE', 'CONFIRMED', 'Superintending Engineer'),
('OM-CIR-II',  'O&M Circle II',    'Circle', 'OM', 'Operations & Maintenance Wing', 'OM-CIR-II', 'O&M Circle II',    2, 'OM', (SELECT id FROM public.hmwssb_divisions WHERE division_code='WING-OM'), 11, 'ACTIVE', 'CONFIRMED', 'Superintending Engineer'),
('OM-CIR-III', 'O&M Circle III',   'Circle', 'OM', 'Operations & Maintenance Wing', 'OM-CIR-III', 'O&M Circle III',   2, 'OM', (SELECT id FROM public.hmwssb_divisions WHERE division_code='WING-OM'), 12, 'ACTIVE', 'CONFIRMED', 'Superintending Engineer'),
('OM-CIR-IV',  'O&M Circle IV',    'Circle', 'OM', 'Operations & Maintenance Wing', 'OM-CIR-IV', 'O&M Circle IV',    2, 'OM', (SELECT id FROM public.hmwssb_divisions WHERE division_code='WING-OM'), 13, 'ACTIVE', 'CONFIRMED', 'Superintending Engineer');

-- Project Wing Circles (PCCs)
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, wing_code, wing_name, circle_code, circle_name, hierarchy_level, parent_group, parent_division_id, display_order, status, verification_status, head_designation)
VALUES
('PCC-I',      'PCC-I',            'Circle', 'PROJECTS', 'Project Wing', 'PCC-I', 'PCC-I',            2, 'PROJECTS', (SELECT id FROM public.hmwssb_divisions WHERE division_code='WING-PRJ'), 20, 'ACTIVE', 'CONFIRMED', 'Superintending Engineer'),
('PCC-II',     'PCC-II',           'Circle', 'PROJECTS', 'Project Wing', 'PCC-II', 'PCC-II',           2, 'PROJECTS', (SELECT id FROM public.hmwssb_divisions WHERE division_code='WING-PRJ'), 21, 'ACTIVE', 'CONFIRMED', 'Superintending Engineer'),
('PCC-III',    'PCC-III',          'Circle', 'PROJECTS', 'Project Wing', 'PCC-III', 'PCC-III',          2, 'PROJECTS', (SELECT id FROM public.hmwssb_divisions WHERE division_code='WING-PRJ'), 22, 'INACTIVE', 'PENDING_VERIFICATION', 'Superintending Engineer');

-- Transmission Wing Circle
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, wing_code, wing_name, circle_code, circle_name, hierarchy_level, parent_group, parent_division_id, display_order, status, verification_status, head_designation)
VALUES
('TRN-CIR',    'Transmission Circle', 'Circle', 'TRANSMISSION', 'Transmission Wing', 'TRN-CIR', 'Transmission Circle', 2, 'TRANSMISSION', (SELECT id FROM public.hmwssb_divisions WHERE division_code='WING-TRN'), 30, 'ACTIVE', 'CONFIRMED', 'Superintending Engineer');

-- Special Engineering Wing Circles
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, wing_code, wing_name, circle_code, circle_name, hierarchy_level, parent_group, parent_division_id, display_order, status, verification_status, head_designation)
VALUES
('STP-CIR-I',  'STP Circle I',     'Circle', 'STP_ELECTRICAL', 'Special Engineering Wing', 'STP-CIR-I', 'STP Circle I',  2, 'STP_ELECTRICAL', (SELECT id FROM public.hmwssb_divisions WHERE division_code='WING-SE'), 40, 'ACTIVE', 'CONFIRMED', 'Superintending Engineer'),
('STP-CIR-II', 'STP Circle II',    'Circle', 'STP_ELECTRICAL', 'Special Engineering Wing', 'STP-CIR-II', 'STP Circle II', 2, 'STP_ELECTRICAL', (SELECT id FROM public.hmwssb_divisions WHERE division_code='WING-SE'), 41, 'ACTIVE', 'CONFIRMED', 'Superintending Engineer'),
('ELE-CIR',    'Electrical Circle', 'Circle', 'STP_ELECTRICAL', 'Special Engineering Wing', 'ELE-CIR', 'Electrical Circle', 2, 'STP_ELECTRICAL', (SELECT id FROM public.hmwssb_divisions WHERE division_code='WING-SE'), 42, 'ACTIVE', 'CONFIRMED', 'Superintending Engineer');

-- Quality & Stores Wing Circles
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, wing_code, wing_name, circle_code, circle_name, hierarchy_level, parent_group, parent_division_id, display_order, status, verification_status, head_designation)
VALUES
('QC-CIR',     'Quality Control Circle', 'Circle', 'QUALITY_STORES', 'Quality & Stores Wing', 'QC-CIR', 'Quality Control Circle', 2, 'QUALITY_STORES', (SELECT id FROM public.hmwssb_divisions WHERE division_code='WING-QS'), 50, 'ACTIVE', 'CONFIRMED', 'Superintending Engineer'),
('STR-CIR',    'Stores Circle',          'Circle', 'QUALITY_STORES', 'Quality & Stores Wing', 'STR-CIR', 'Stores Circle', 2, 'QUALITY_STORES', (SELECT id FROM public.hmwssb_divisions WHERE division_code='WING-QS'), 51, 'ACTIVE', 'CONFIRMED', 'Superintending Engineer');

-- Board Administration Wing Circles
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, wing_code, wing_name, circle_code, circle_name, hierarchy_level, parent_group, parent_division_id, display_order, status, verification_status, head_designation)
VALUES
('BOARD-CIR',  'Board Office Circle',    'Circle', 'BOARD_ADMIN', 'Board Administration Wing', 'BOARD-CIR', 'Board Office Circle', 2, 'BOARD_ADMIN', (SELECT id FROM public.hmwssb_divisions WHERE division_code='WING-BA'), 60, 'ACTIVE', 'CONFIRMED', 'Executive Director');

-- ============================================================================
-- DIVISION LEVEL (Level 3) - 48 Administrative Divisions
-- ============================================================================

-- ---------------------------------------------------------------
-- O&M Wing: 24 Divisions across 4 Circles (6 each)
-- ---------------------------------------------------------------

-- O&M Circle I (Central Zone) - Divisions I to VI
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, wing_code, wing_name, circle_code, circle_name, hierarchy_level, parent_group, parent_division_id, display_order, status, verification_status, area_coverage, head_designation)
VALUES
('OM-DIV-01', 'O&M Division I',   'Division', 'OM', 'Operations & Maintenance Wing', 'OM-CIR-I', 'O&M Circle I', 3, 'OM', (SELECT id FROM public.hmwssb_divisions WHERE division_code='OM-CIR-I'), 100, 'ACTIVE', 'CONFIRMED', 'Kairatabad / Asif Nagar', 'Executive Engineer'),
('OM-DIV-02', 'O&M Division II',  'Division', 'OM', 'Operations & Maintenance Wing', 'OM-CIR-I', 'O&M Circle I', 3, 'OM', (SELECT id FROM public.hmwssb_divisions WHERE division_code='OM-CIR-I'), 101, 'ACTIVE', 'CONFIRMED', 'Srinivasa Nagar / SR Nagar', 'Executive Engineer'),
('OM-DIV-03', 'O&M Division III', 'Division', 'OM', 'Operations & Maintenance Wing', 'OM-CIR-I', 'O&M Circle I', 3, 'OM', (SELECT id FROM public.hmwssb_divisions WHERE division_code='OM-CIR-I'), 102, 'ACTIVE', 'CONFIRMED', 'Narayanaguda / Himayatnagar', 'Executive Engineer'),
('OM-DIV-04', 'O&M Division IV',  'Division', 'OM', 'Operations & Maintenance Wing', 'OM-CIR-I', 'O&M Circle I', 3, 'OM', (SELECT id FROM public.hmwssb_divisions WHERE division_code='OM-CIR-I'), 103, 'ACTIVE', 'CONFIRMED', 'Charminar / Old City West', 'Executive Engineer'),
('OM-DIV-05', 'O&M Division V',   'Division', 'OM', 'Operations & Maintenance Wing', 'OM-CIR-I', 'O&M Circle I', 3, 'OM', (SELECT id FROM public.hmwssb_divisions WHERE division_code='OM-CIR-I'), 104, 'ACTIVE', 'CONFIRMED', 'Moghalpura / Old City East', 'Executive Engineer'),
('OM-DIV-06', 'O&M Division VI',  'Division', 'OM', 'Operations & Maintenance Wing', 'OM-CIR-I', 'O&M Circle I', 3, 'OM', (SELECT id FROM public.hmwssb_divisions WHERE division_code='OM-CIR-I'), 105, 'ACTIVE', 'CONFIRMED', 'Secunderabad / Board Area', 'Executive Engineer');

-- O&M Circle II (North-East Zone) - Divisions VII to XII
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, wing_code, wing_name, circle_code, circle_name, hierarchy_level, parent_group, parent_division_id, display_order, status, verification_status, area_coverage, head_designation)
VALUES
('OM-DIV-07', 'O&M Division VII',   'Division', 'OM', 'Operations & Maintenance Wing', 'OM-CIR-II', 'O&M Circle II', 3, 'OM', (SELECT id FROM public.hmwssb_divisions WHERE division_code='OM-CIR-II'), 110, 'ACTIVE', 'CONFIRMED', 'Malkajgiri Circle', 'Executive Engineer'),
('OM-DIV-08', 'O&M Division VIII',  'Division', 'OM', 'Operations & Maintenance Wing', 'OM-CIR-II', 'O&M Circle II', 3, 'OM', (SELECT id FROM public.hmwssb_divisions WHERE division_code='OM-CIR-II'), 111, 'ACTIVE', 'CONFIRMED', 'Alwal Circle', 'Executive Engineer'),
('OM-DIV-09', 'O&M Division IX',    'Division', 'OM', 'Operations & Maintenance Wing', 'OM-CIR-II', 'O&M Circle II', 3, 'OM', (SELECT id FROM public.hmwssb_divisions WHERE division_code='OM-CIR-II'), 112, 'ACTIVE', 'CONFIRMED', 'Kukatpally Circle', 'Executive Engineer'),
('OM-DIV-10', 'O&M Division X',     'Division', 'OM', 'Operations & Maintenance Wing', 'OM-CIR-II', 'O&M Circle II', 3, 'OM', (SELECT id FROM public.hmwssb_divisions WHERE division_code='OM-CIR-II'), 113, 'ACTIVE', 'CONFIRMED', 'Quthbullapur Circle', 'Executive Engineer'),
('OM-DIV-11', 'O&M Division XI',    'Division', 'OM', 'Operations & Maintenance Wing', 'OM-CIR-II', 'O&M Circle II', 3, 'OM', (SELECT id FROM public.hmwssb_divisions WHERE division_code='OM-CIR-II'), 114, 'ACTIVE', 'CONFIRMED', 'Balanagar Area', 'Executive Engineer'),
('OM-DIV-12', 'O&M Division XII',   'Division', 'OM', 'Operations & Maintenance Wing', 'OM-CIR-II', 'O&M Circle II', 3, 'OM', (SELECT id FROM public.hmwssb_divisions WHERE division_code='OM-CIR-II'), 115, 'ACTIVE', 'CONFIRMED', 'Uppal Circle', 'Executive Engineer');

-- O&M Circle III (South-East Zone) - Divisions XIII to XVIII
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, wing_code, wing_name, circle_code, circle_name, hierarchy_level, parent_group, parent_division_id, display_order, status, verification_status, area_coverage, head_designation)
VALUES
('OM-DIV-13', 'O&M Division XIII',  'Division', 'OM', 'Operations & Maintenance Wing', 'OM-CIR-III', 'O&M Circle III', 3, 'OM', (SELECT id FROM public.hmwssb_divisions WHERE division_code='OM-CIR-III'), 120, 'ACTIVE', 'CONFIRMED', 'LB Nagar Circle', 'Executive Engineer'),
('OM-DIV-14', 'O&M Division XIV',   'Division', 'OM', 'Operations & Maintenance Wing', 'OM-CIR-III', 'O&M Circle III', 3, 'OM', (SELECT id FROM public.hmwssb_divisions WHERE division_code='OM-CIR-III'), 121, 'ACTIVE', 'CONFIRMED', 'Gaddiannaram / Saroornagar', 'Executive Engineer'),
('OM-DIV-15', 'O&M Division XV',    'Division', 'OM', 'Operations & Maintenance Wing', 'OM-CIR-III', 'O&M Circle III', 3, 'OM', (SELECT id FROM public.hmwssb_divisions WHERE division_code='OM-CIR-III'), 122, 'ACTIVE', 'CONFIRMED', 'Maheshwaram Circle', 'Executive Engineer'),
('OM-DIV-16', 'O&M Division XVI',   'Division', 'OM', 'Operations & Maintenance Wing', 'OM-CIR-III', 'O&M Circle III', 3, 'OM', (SELECT id FROM public.hmwssb_divisions WHERE division_code='OM-CIR-III'), 123, 'ACTIVE', 'CONFIRMED', 'Rajendranagar Circle', 'Executive Engineer'),
('OM-DIV-17', 'O&M Division XVII',  'Division', 'OM', 'Operations & Maintenance Wing', 'OM-CIR-III', 'O&M Circle III', 3, 'OM', (SELECT id FROM public.hmwssb_divisions WHERE division_code='OM-CIR-III'), 124, 'ACTIVE', 'CONFIRMED', 'Chandrayangutta', 'Executive Engineer'),
('OM-DIV-18', 'O&M Division XVIII', 'Division', 'OM', 'Operations & Maintenance Wing', 'OM-CIR-III', 'O&M Circle III', 3, 'OM', (SELECT id FROM public.hmwssb_divisions WHERE division_code='OM-CIR-III'), 125, 'ACTIVE', 'CONFIRMED', 'Yakutpura / Santoshnagar', 'Executive Engineer');

-- O&M Circle IV (Outer West & Peripheral) - Divisions XIX to XXIV
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, wing_code, wing_name, circle_code, circle_name, hierarchy_level, parent_group, parent_division_id, display_order, status, verification_status, area_coverage, head_designation)
VALUES
('OM-DIV-19', 'O&M Division XIX',  'Division', 'OM', 'Operations & Maintenance Wing', 'OM-CIR-IV', 'O&M Circle IV', 3, 'OM', (SELECT id FROM public.hmwssb_divisions WHERE division_code='OM-CIR-IV'), 130, 'ACTIVE', 'CONFIRMED', 'Serilingampally West', 'Executive Engineer'),
('OM-DIV-20', 'O&M Division XX',   'Division', 'OM', 'Operations & Maintenance Wing', 'OM-CIR-IV', 'O&M Circle IV', 3, 'OM', (SELECT id FROM public.hmwssb_divisions WHERE division_code='OM-CIR-IV'), 131, 'ACTIVE', 'CONFIRMED', 'Madhapur / IT Corridor', 'Executive Engineer'),
('OM-DIV-21', 'O&M Division XXI',  'Division', 'OM', 'Operations & Maintenance Wing', 'OM-CIR-IV', 'O&M Circle IV', 3, 'OM', (SELECT id FROM public.hmwssb_divisions WHERE division_code='OM-CIR-IV'), 132, 'ACTIVE', 'CONFIRMED', 'RC Puram / Patancheru', 'Executive Engineer'),
('OM-DIV-22', 'O&M Division XXII', 'Division', 'OM', 'Operations & Maintenance Wing', 'OM-CIR-IV', 'O&M Circle IV', 3, 'OM', (SELECT id FROM public.hmwssb_divisions WHERE division_code='OM-CIR-IV'), 133, 'ACTIVE', 'CONFIRMED', 'Miyapur / Bachupally', 'Executive Engineer'),
('OM-DIV-23', 'O&M Division XXIII','Division', 'OM', 'Operations & Maintenance Wing', 'OM-CIR-IV', 'O&M Circle IV', 3, 'OM', (SELECT id FROM public.hmwssb_divisions WHERE division_code='OM-CIR-IV'), 134, 'ACTIVE', 'CONFIRMED', 'Jeetimetla / Quthbullapur Zone', 'Executive Engineer'),
('OM-DIV-24', 'O&M Division XXIV', 'Division', 'OM', 'Operations & Maintenance Wing', 'OM-CIR-IV', 'O&M Circle IV', 3, 'OM', (SELECT id FROM public.hmwssb_divisions WHERE division_code='OM-CIR-IV'), 135, 'ACTIVE', 'CONFIRMED', 'Expanded Circle Boundary Division', 'Executive Engineer');

-- ---------------------------------------------------------------
-- Project Wing: 8 Project Divisions across 3 PCCs
-- ---------------------------------------------------------------

-- PCC-I (2 Divisions)
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, wing_code, wing_name, circle_code, circle_name, hierarchy_level, parent_group, parent_division_id, display_order, status, verification_status, area_coverage, head_designation)
VALUES
('PRJ-DIV-01', 'Project Division I',  'Division', 'PROJECTS', 'Project Wing', 'PCC-I', 'PCC-I', 3, 'PROJECTS', (SELECT id FROM public.hmwssb_divisions WHERE division_code='PCC-I'), 200, 'ACTIVE', 'CONFIRMED', 'STP Construction & Sewerage Trunk Network', 'Executive Engineer'),
('PRJ-DIV-02', 'Project Division II', 'Division', 'PROJECTS', 'Project Wing', 'PCC-I', 'PCC-I', 3, 'PROJECTS', (SELECT id FROM public.hmwssb_divisions WHERE division_code='PCC-I'), 201, 'ACTIVE', 'CONFIRMED', 'Water Grid Augmentation & Master Reservoirs', 'Executive Engineer');

-- PCC-II (2 Divisions)
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, wing_code, wing_name, circle_code, circle_name, hierarchy_level, parent_group, parent_division_id, display_order, status, verification_status, area_coverage, head_designation)
VALUES
('PRJ-DIV-03', 'Project Division III', 'Division', 'PROJECTS', 'Project Wing', 'PCC-II', 'PCC-II', 3, 'PROJECTS', (SELECT id FROM public.hmwssb_divisions WHERE division_code='PCC-II'), 210, 'ACTIVE', 'CONFIRMED', 'HUDCO / AMRUT Funded Projects', 'Executive Engineer'),
('PRJ-DIV-04', 'Project Division IV',  'Division', 'PROJECTS', 'Project Wing', 'PCC-II', 'PCC-II', 3, 'PROJECTS', (SELECT id FROM public.hmwssb_divisions WHERE division_code='PCC-II'), 211, 'ACTIVE', 'CONFIRMED', 'ORR Outer Ring Road Phase Projects', 'Executive Engineer');

-- PCC-III (4 Divisions) - Inactive/Disputed
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, wing_code, wing_name, circle_code, circle_name, hierarchy_level, parent_group, parent_division_id, display_order, status, verification_status, area_coverage, head_designation)
VALUES
('PRJ-DIV-05', 'Project Division V',  'Division', 'PROJECTS', 'Project Wing', 'PCC-III', 'PCC-III', 3, 'PROJECTS', (SELECT id FROM public.hmwssb_divisions WHERE division_code='PCC-III'), 220, 'INACTIVE', 'PENDING_VERIFICATION', 'Peripheral Municipalities Network Expansion', 'Executive Engineer'),
('PRJ-DIV-06', 'Project Division VI', 'Division', 'PROJECTS', 'Project Wing', 'PCC-III', 'PCC-III', 3, 'PROJECTS', (SELECT id FROM public.hmwssb_divisions WHERE division_code='PCC-III'), 221, 'INACTIVE', 'PENDING_VERIFICATION', 'Godavari Phase Water Intake & Treatment', 'Executive Engineer'),
('PRJ-DIV-07', 'Project Division VII','Division', 'PROJECTS', 'Project Wing', 'PCC-III', 'PCC-III', 3, 'PROJECTS', (SELECT id FROM public.hmwssb_divisions WHERE division_code='PCC-III'), 222, 'INACTIVE', 'PENDING_VERIFICATION', 'Krishna Phase Water Supply Works', 'Executive Engineer'),
('PRJ-DIV-08', 'Project Division VIII','Division', 'PROJECTS', 'Project Wing', 'PCC-III', 'PCC-III', 3, 'PROJECTS', (SELECT id FROM public.hmwssb_divisions WHERE division_code='PCC-III'), 223, 'INACTIVE', 'PENDING_VERIFICATION', 'Special Infrastructure & Rehabilitation', 'Executive Engineer');

-- ---------------------------------------------------------------
-- Transmission Wing: 5 Divisions under Transmission Circle
-- ---------------------------------------------------------------
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, wing_code, wing_name, circle_code, circle_name, hierarchy_level, parent_group, parent_division_id, display_order, status, verification_status, area_coverage, head_designation)
VALUES
('TRN-DIV-01', 'Transmission Circle I',  'Division', 'TRANSMISSION', 'Transmission Wing', 'TRN-CIR', 'Transmission Circle', 3, 'TRANSMISSION', (SELECT id FROM public.hmwssb_divisions WHERE division_code='TRN-CIR'), 300, 'ACTIVE', 'CONFIRMED', 'Krishna Phase I, II, III Pumping Mains', 'Executive Engineer'),
('TRN-DIV-02', 'Transmission Circle II', 'Division', 'TRANSMISSION', 'Transmission Wing', 'TRN-CIR', 'Transmission Circle', 3, 'TRANSMISSION', (SELECT id FROM public.hmwssb_divisions WHERE division_code='TRN-CIR'), 301, 'ACTIVE', 'CONFIRMED', 'Godavari Phase I Pumping Mains & Treatment', 'Executive Engineer'),
('TRN-DIV-03', 'Transmission Circle III','Division', 'TRANSMISSION', 'Transmission Wing', 'TRN-CIR', 'Transmission Circle', 3, 'TRANSMISSION', (SELECT id FROM public.hmwssb_divisions WHERE division_code='TRN-CIR'), 302, 'ACTIVE', 'CONFIRMED', 'Singur / Manjira Bulk Pumping Stations', 'Executive Engineer'),
('TRN-DIV-04', 'Transmission Circle IV', 'Division', 'TRANSMISSION', 'Transmission Wing', 'TRN-CIR', 'Transmission Circle', 3, 'TRANSMISSION', (SELECT id FROM public.hmwssb_divisions WHERE division_code='TRN-CIR'), 303, 'ACTIVE', 'CONFIRMED', 'Sewerage Main Trunk Pressure Lines', 'Executive Engineer'),
('TRN-DIV-05', 'Transmission Circle V',  'Division', 'TRANSMISSION', 'Transmission Wing', 'TRN-CIR', 'Transmission Circle', 3, 'TRANSMISSION', (SELECT id FROM public.hmwssb_divisions WHERE division_code='TRN-CIR'), 304, 'ACTIVE', 'CONFIRMED', 'Central Pumping Grid Headquarters', 'Executive Engineer');

-- ---------------------------------------------------------------
-- Special Engineering Wing: Electrical, STP, etc.
-- ---------------------------------------------------------------
-- STP Circle I (1 Division)
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, wing_code, wing_name, circle_code, circle_name, hierarchy_level, parent_group, parent_division_id, display_order, status, verification_status, area_coverage, head_designation)
VALUES
('STP-DIV-01', 'STP Division I', 'Division', 'STP_ELECTRICAL', 'Special Engineering Wing', 'STP-CIR-I', 'STP Circle I', 3, 'STP_ELECTRICAL', (SELECT id FROM public.hmwssb_divisions WHERE division_code='STP-CIR-I'), 400, 'ACTIVE', 'CONFIRMED', 'Sewage Treatment Plants - Zone I', 'Executive Engineer');

-- STP Circle II (1 Division)
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, wing_code, wing_name, circle_code, circle_name, hierarchy_level, parent_group, parent_division_id, display_order, status, verification_status, area_coverage, head_designation)
VALUES
('STP-DIV-02', 'STP Division II', 'Division', 'STP_ELECTRICAL', 'Special Engineering Wing', 'STP-CIR-II', 'STP Circle II', 3, 'STP_ELECTRICAL', (SELECT id FROM public.hmwssb_divisions WHERE division_code='STP-CIR-II'), 410, 'ACTIVE', 'CONFIRMED', 'Sewage Treatment Plants - Zone II', 'Executive Engineer');

-- Electrical Circle (1 Division)
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, wing_code, wing_name, circle_code, circle_name, hierarchy_level, parent_group, parent_division_id, display_order, status, verification_status, area_coverage, head_designation)
VALUES
('ELE-DIV-01', 'Electrical Project Division', 'Division', 'STP_ELECTRICAL', 'Special Engineering Wing', 'ELE-CIR', 'Electrical Circle', 3, 'STP_ELECTRICAL', (SELECT id FROM public.hmwssb_divisions WHERE division_code='ELE-CIR'), 420, 'ACTIVE', 'CONFIRMED', 'High-tension power substations, transformers, heavy motor pumps', 'Executive Engineer');

-- ---------------------------------------------------------------
-- Quality & Stores Wing: 2 Divisions
-- ---------------------------------------------------------------
-- Quality Control Circle (1 Division)
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, wing_code, wing_name, circle_code, circle_name, hierarchy_level, parent_group, parent_division_id, display_order, status, verification_status, area_coverage, head_designation)
VALUES
('QC-DIV-01', 'Quality Control & Vigilance Division', 'Division', 'QUALITY_STORES', 'Quality & Stores Wing', 'QC-CIR', 'Quality Control Circle', 3, 'QUALITY_STORES', (SELECT id FROM public.hmwssb_divisions WHERE division_code='QC-CIR'), 500, 'ACTIVE', 'CONFIRMED', 'Water quality monitoring labs, vigilance enforcement, pipe material testing', 'Executive Engineer');

-- Stores Circle (1 Division)
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, wing_code, wing_name, circle_code, circle_name, hierarchy_level, parent_group, parent_division_id, display_order, status, verification_status, area_coverage, head_designation)
VALUES
('STR-DIV-01', 'Central Stores Division', 'Division', 'QUALITY_STORES', 'Quality & Stores Wing', 'STR-CIR', 'Stores Circle', 3, 'QUALITY_STORES', (SELECT id FROM public.hmwssb_divisions WHERE division_code='STR-CIR'), 510, 'ACTIVE', 'CONFIRMED', 'Procurement and distribution of pipes, valves, water meters, repair hardware', 'Executive Engineer');

-- ---------------------------------------------------------------
-- Board Administration Wing: 1 Division
-- ---------------------------------------------------------------
INSERT INTO public.hmwssb_divisions (division_code, division_name, category, wing_code, wing_name, circle_code, circle_name, hierarchy_level, parent_group, parent_division_id, display_order, status, verification_status, area_coverage, head_designation)
VALUES
('BOARD-DIV-01', 'Board Office', 'Division', 'BOARD_ADMIN', 'Board Administration Wing', 'BOARD-CIR', 'Board Office Circle', 3, 'BOARD_ADMIN', (SELECT id FROM public.hmwssb_divisions WHERE division_code='BOARD-CIR'), 600, 'ACTIVE', 'CONFIRMED', 'HRMS administration, executive secretariat, finance/payroll audit, IT data center', 'Executive Director');

-- ============================================================================
-- Verification: Total Active Divisions = 48 (Level 3 only)
-- ============================================================================
-- SELECT wing_code, wing_name, COUNT(*) as division_count 
-- FROM public.hmwssb_divisions 
-- WHERE hierarchy_level = 3 AND status = 'ACTIVE' 
-- GROUP BY wing_code, wing_name 
-- ORDER BY MIN(display_order);
-- Expected: OM=24, PROJECTS=4 (active), TRANSMISSION=5, STP_ELECTRICAL=3, QUALITY_STORES=2, BOARD_ADMIN=1 = 39 active divisions
-- (PCC-III has 4 inactive divisions)

COMMIT;