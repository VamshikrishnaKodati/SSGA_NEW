import test from 'node:test'
import assert from 'node:assert/strict'
import { canonicalRole, createAbility, visibleNav, ROLES } from './permissions.js'

function navigationFor(authorities) {
  const ability = createAbility({ authorities })
  return visibleNav(ability).flatMap((group) => group.items.map((item) => item.id))
}

test('payroll operators can reach import and payroll preparation', () => {
  const nav = navigationFor([ROLES.PAYROLL_OPERATOR, 'payroll.prepare'])

  assert.ok(nav.includes('imports'))
  assert.ok(nav.includes('payroll'))
  assert.ok(!nav.includes('approvals'))
})

test('only organization administrators can manage shared payroll periods', () => {
  const orgAdminNav = navigationFor([
    ROLES.ORG_ADMIN,
    'organization.manage',
    'payroll.period.manage',
  ])
  const divisionAdminNav = navigationFor([
    ROLES.DIVISION_ADMIN,
  ])

  assert.ok(orgAdminNav.includes('payroll'))
  assert.ok(!divisionAdminNav.includes('imports'))
  assert.ok(!createAbility({
    authorities: [ROLES.DIVISION_ADMIN, 'payroll.prepare'],
  }).has('payroll.period.manage'))
})

test('approving authorities can review payroll without import access', () => {
  const nav = navigationFor([ROLES.APPROVING_AUTHORITY, 'payroll.approve', 'payroll.finalize'])

  assert.ok(nav.includes('approvals'))
  assert.ok(nav.includes('payroll'))
  assert.ok(nav.includes('statements'))
  assert.ok(!nav.includes('imports'))
})

test('report users receive reporting access without payroll mutation access', () => {
  const nav = navigationFor([ROLES.REPORT_USER, 'report.view', 'payroll.view'])

  assert.ok(nav.includes('reports'))
  assert.ok(nav.includes('payroll'))
  assert.ok(!nav.includes('imports'))
  assert.ok(!nav.includes('approvals'))
})

test('auditors see read-only audit and report navigation', () => {
  const ability = createAbility({
    authorities: [ROLES.AUDITOR, 'audit.view', 'report.view'],
  })
  const navGroups = visibleNav(ability)
  const nav = navGroups.flatMap((group) => group.items.map((item) => item.id))
  const auditItem = navGroups.flatMap((group) => group.items)
    .find((item) => item.id === 'audit')

  assert.ok(nav.includes('audit'))
  assert.ok(nav.includes('reports'))
  assert.equal(auditItem.availability, undefined)
  assert.ok(!nav.includes('imports'))
  assert.ok(!nav.includes('payroll'))
})

test('legacy role codes resolve to canonical roles', () => {
  const ability = createAbility({
    authorities: ['ROLE_SYS_ADMIN', 'payroll.view'],
  })

  assert.equal(canonicalRole('SYS_ADMIN'), ROLES.SYSTEM_ADMIN)
  assert.equal(canonicalRole('ROLE_GM'), ROLES.APPROVING_AUTHORITY)
  assert.equal(canonicalRole('SO_FINANCE'), ROLES.PAYROLL_OPERATOR)
  assert.ok(ability.isAdmin)
  assert.ok(ability.hasRole(ROLES.SYSTEM_ADMIN))
  assert.equal(ability.primaryRole, ROLES.SYSTEM_ADMIN)
})

test('employees only see self-service statements and profile navigation', () => {
  const nav = navigationFor([ROLES.EMPLOYEE_SELF, 'statement.view', 'statement.download'])

  assert.ok(nav.includes('statements'))
  assert.ok(nav.includes('profile'))
  assert.ok(!nav.includes('employees'))
  assert.ok(!nav.includes('imports'))
  assert.ok(!nav.includes('reports'))
})

// EMPLOYEE holds only payslip.view_self. A statement gate that checks
// statement.view alone left the employee with no statements workspace at all.
test('an employee holding only payslip.view_self still reaches statements', () => {
  const nav = navigationFor([ROLES.EMPLOYEE_SELF, 'payslip.view_self'])

  assert.ok(nav.includes('statements'))
  assert.ok(nav.includes('profile'))
  assert.ok(!nav.includes('payroll'))
})

// DIV_ADMIN and WING_ADMIN hold user.create / scope.bind, not user.manage.
test('division and wing admins reach user management without user.manage', () => {
  const nav = navigationFor([
    ROLES.DIVISION_ADMIN,
    'user.create',
    'user.update',
    'user.deactivate',
    'scope.bind',
  ])

  assert.ok(nav.includes('access'))
})

// The System Administrator workspace. SYS_ADMIN holds the explicit spec grants,
// not a wildcard, so the nav has to open on real permission strings.
test('system administrators reach user and role management', () => {
  const nav = navigationFor([
    ROLES.SYSTEM_ADMIN,
    'user.manage',
    'user.create',
    'user.update',
    'user.view',
    'role.assign',
    'role.view',
    'scope.bind',
  ])

  assert.ok(nav.includes('access'))
  assert.ok(nav.includes('roles'))
  assert.ok(nav.includes('system'))
  assert.ok(nav.includes('audit'))
})

// A role that can assign permissions must not automatically reach user records.
test('role assignment alone does not open user management', () => {
  const nav = navigationFor([ROLES.AUDITOR, 'role.assign'])

  assert.ok(!nav.includes('access'))
  assert.ok(nav.includes('roles'))
})
