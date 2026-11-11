export const ROLES = {
  SYSTEM_ADMIN: 'ROLE_SYSTEM_ADMIN',
  ORG_ADMIN: 'ROLE_ORG_ADMIN',
  WING_ADMIN: 'ROLE_WING_ADMIN',
  DIVISION_ADMIN: 'ROLE_DIVISION_ADMIN',
  PAYROLL_OPERATOR: 'ROLE_PAYROLL_OPERATOR',
  APPROVING_AUTHORITY: 'ROLE_APPROVING_AUTHORITY',
  PAYROLL_FINALIZER: 'ROLE_PAYROLL_FINALIZER',
  REPORT_USER: 'ROLE_REPORT_USER',
  EMPLOYEE_SELF: 'ROLE_EMPLOYEE_SELF',
  AUDITOR: 'ROLE_AUDITOR',
  HR_ADMIN: 'ROLE_HR_ADMIN',
  DATA_IMPORT_OPERATOR: 'ROLE_DATA_IMPORT_OPERATOR',
  IMPORT_CHECKER: 'ROLE_IMPORT_CHECKER',
  IMPORT_COMMITTER: 'ROLE_IMPORT_COMMITTER',
  PAYROLL_CHECKER: 'ROLE_PAYROLL_CHECKER',
}

export const ROLE_LABELS = {
  [ROLES.SYSTEM_ADMIN]: 'System Administrator',
  [ROLES.ORG_ADMIN]: 'Organization Administrator',
  [ROLES.WING_ADMIN]: 'Wing Administrator',
  [ROLES.DIVISION_ADMIN]: 'Division Administrator',
  [ROLES.PAYROLL_OPERATOR]: 'Salary / Payroll Operator',
  [ROLES.APPROVING_AUTHORITY]: 'Approving Authority',
  [ROLES.PAYROLL_FINALIZER]: 'Payroll Finalizer',
  [ROLES.REPORT_USER]: 'Report User',
  [ROLES.EMPLOYEE_SELF]: 'Employee (self service)',
  [ROLES.AUDITOR]: 'Auditor',
  [ROLES.HR_ADMIN]: 'HR Administrator',
  [ROLES.DATA_IMPORT_OPERATOR]: 'Data Import Operator',
  [ROLES.IMPORT_CHECKER]: 'Import Checker',
  [ROLES.IMPORT_COMMITTER]: 'Import Committer',
  [ROLES.PAYROLL_CHECKER]: 'Payroll Checker',
}

const ROLE_ALIASES = {
  SYS_ADMIN: ROLES.SYSTEM_ADMIN,
  DIV_ADMIN: ROLES.DIVISION_ADMIN,
  EMPLOYEE: ROLES.EMPLOYEE_SELF,
  GM: ROLES.APPROVING_AUTHORITY,
  SO_FINANCE: ROLES.PAYROLL_OPERATOR,
  IT_ADMIN: ROLES.SYSTEM_ADMIN,
  OFF_PAYROLL: ROLES.PAYROLL_OPERATOR,
  HR_ADMINISTRATOR: ROLES.HR_ADMIN,
  IMPORT_OPERATOR: ROLES.DATA_IMPORT_OPERATOR,
}

export function canonicalRole(roleCode) {
  if (!roleCode) return null
  const code = String(roleCode).replace(/^ROLE_/, '')
  return ROLE_ALIASES[code] || `ROLE_${code}`
}

const ROLE_PRIORITY = [
  ROLES.SYSTEM_ADMIN,
  ROLES.ORG_ADMIN,
  ROLES.WING_ADMIN,
  ROLES.DIVISION_ADMIN,
  ROLES.HR_ADMIN,
  ROLES.DATA_IMPORT_OPERATOR,
  ROLES.IMPORT_CHECKER,
  ROLES.IMPORT_COMMITTER,
  ROLES.PAYROLL_CHECKER,
  ROLES.APPROVING_AUTHORITY,
  ROLES.PAYROLL_FINALIZER,
  ROLES.PAYROLL_OPERATOR,
  ROLES.AUDITOR,
  ROLES.REPORT_USER,
  ROLES.EMPLOYEE_SELF,
]

// The full permission catalogue defined in database/02_security.sql.
// Only these strings are ever granted by the backend.
export const PERMISSIONS = [
  'organization.view',
  'organization.manage',
  'wing.view',
  'wing.manage',
  'division.view',
  'division.manage',
  'employee.view',
  'employee.create',
  'employee.update',
  'employee.hrms_replace',
  'salary.view',
  'salary.manage',
  'salary.prepare',
  'payroll.view',
  'payroll.prepare',
  'payroll.verify',
  'payroll.submit',
  'payroll.approve',
  'payroll.finalize',
  'payroll.reopen',
  'payroll.period.manage',
  'statement.view',
  'statement.generate',
  'statement.reissue',
  'statement.download',
  'report.view',
  'export.download',
  'user.manage',
  'scope.manage',
  'audit.view',
  'import.commit',
]

export function createAbility(user) {
  const authorities = Array.isArray(user?.authorities) ? user.authorities : []
  const set = new Set(
    authorities.map((authority) => (
      authority.startsWith('ROLE_') || ROLE_ALIASES[authority]
        ? canonicalRole(authority)
        : authority
    )),
  )
  const isAdmin = set.has(ROLES.SYSTEM_ADMIN)

  return {
    authorities,
    isAdmin,
    roles: ROLE_PRIORITY.filter((role) => set.has(role)),
    has: (code) => isAdmin || set.has(code),
    hasAny: (...codes) => isAdmin || codes.some((code) => set.has(code)),
    hasRole: (role) => set.has(canonicalRole(role)),
    primaryRole: ROLE_PRIORITY.find((role) => set.has(role)) || null,
    importMode: () => {
      if (set.has(ROLES.HR_ADMIN) || set.has(ROLES.ORG_ADMIN) || set.has(ROLES.WING_ADMIN) || set.has(ROLES.DIVISION_ADMIN)) return 'HR'
      if (set.has(ROLES.DATA_IMPORT_OPERATOR)) return 'IMPORT_OPERATOR'
      if (set.has(ROLES.IMPORT_CHECKER)) return 'IMPORT_CHECKER'
      if (set.has(ROLES.IMPORT_COMMITTER)) return 'IMPORT_COMMITTER'
      if (set.has(ROLES.PAYROLL_CHECKER)) return 'PAYROLL_CHECKER'
      return 'GENERAL'
    },
  }
}

export function primaryRoleLabel(user) {
  const ability = createAbility(user)
  return ability.primaryRole ? ROLE_LABELS[ability.primaryRole] : 'Authorized user'
}

// Navigation catalogue. Every item is gated on real permissions returned by
// the backend; `availability` marks modules whose backing endpoint does not
// exist yet so the shell can label them honestly rather than dead-end.
export const NAV_GROUPS = [
  {
    id: 'operations',
    label: 'Operations',
    items: [
      { id: 'dashboard', label: 'Dashboard', icon: 'grid', always: true },
      {
        id: 'payroll',
        label: 'Payroll processing',
        icon: 'calculator',
        any: [
          'payroll.prepare',
          'payroll.verify',
          'payroll.submit',
          'payroll.approve',
          'payroll.finalize',
          'payroll.reopen',
          'payroll.period.manage',
          'payroll.view',
        ],
      },
      {
        id: 'statements',
        label: 'Salary statements',
        icon: 'file',
        any: [
          'statement.view',
          'statement.download',
          'statement.generate',
          'statement.reissue',
          'payroll.finalize',
        ],
      },
      {
        id: 'approvals',
        label: 'Approval inbox',
        icon: 'check',
        any: ['payroll.approve', 'salary.manage'],
      },
      {
        id: 'imports',
        label: 'Data import',
        icon: 'upload',
        any: ['salary.manage', 'payroll.prepare', 'employee.create'],
      },
    ],
  },
  {
    id: 'records',
    label: 'Records',
    items: [
      {
        id: 'employees',
        label: 'Employee master',
        icon: 'users',
        any: ['employee.view'],
      },
      { id: 'profile', label: 'My profile', icon: 'badge', always: true },
    ],
  },
  {
    id: 'insights',
    label: 'Insights',
    items: [
      {
        id: 'reports',
        label: 'Reports',
        icon: 'chart',
        any: ['report.view'],
      },
    ],
  },
  {
    id: 'administration',
    label: 'Administration',
    items: [
      {
        id: 'organization',
        label: 'Organization master',
        icon: 'building',
        any: ['organization.view', 'wing.view', 'division.view'],
      },
      {
        id: 'salary-components',
        label: 'Salary components',
        icon: 'layers',
        any: ['salary.view'],
        availability: 'partial-endpoint',
      },
      {
        id: 'access',
        label: 'User management',
        icon: 'shield',
        any: ['user.manage', 'scope.manage'],
        availability: 'partial-endpoint',
      },
      {
        id: 'audit',
        label: 'Audit trail',
        icon: 'history',
        any: ['audit.view'],
      },
      {
        id: 'notifications',
        label: 'Notifications',
        icon: 'bell',
        always: true,
      },
      {
        id: 'system',
        label: 'System monitor',
        icon: 'server',
        always: false,
        rolesOnly: [ROLES.SYSTEM_ADMIN],
      },
    ],
  },
]

export function visibleNav(ability) {
  return NAV_GROUPS.map((group) => ({
    ...group,
    items: group.items.filter((item) => {
      if (item.always) return true
      if (item.rolesOnly && item.rolesOnly.some((role) => ability.hasRole(role))) return true
      if (item.any && ability.hasAny(...item.any)) return true
      return false
    }),
  })).filter((group) => group.items.length > 0)
}

export function findNavItem(id) {
  for (const group of NAV_GROUPS) {
    const item = group.items.find((entry) => entry.id === id)
    if (item) return item
  }
  return null
}
