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
}

const ROLE_PRIORITY = [
  ROLES.SYSTEM_ADMIN,
  ROLES.ORG_ADMIN,
  ROLES.WING_ADMIN,
  ROLES.DIVISION_ADMIN,
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
]

export function createAbility(user) {
  const authorities = Array.isArray(user?.authorities) ? user.authorities : []
  const set = new Set(authorities)
  const isAdmin = set.has(ROLES.SYSTEM_ADMIN)

  return {
    authorities,
    isAdmin,
    roles: ROLE_PRIORITY.filter((role) => set.has(role)),
    has: (code) => isAdmin || set.has(code),
    hasAny: (...codes) => isAdmin || codes.some((code) => set.has(code)),
    hasRole: (role) => set.has(role),
    primaryRole: ROLE_PRIORITY.find((role) => set.has(role)) || null,
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
        any: ['payroll.approve'],
      },
      {
        id: 'imports',
        label: 'Salary / Data Import',
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
        label: 'User Management',
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
