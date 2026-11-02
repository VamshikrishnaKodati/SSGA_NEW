import test from 'node:test'
import assert from 'node:assert/strict'
import { createAbility, visibleNav, ROLES } from './permissions.js'

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

test('employees only see self-service statements and profile navigation', () => {
  const nav = navigationFor([ROLES.EMPLOYEE_SELF, 'statement.view', 'statement.download'])

  assert.ok(nav.includes('statements'))
  assert.ok(nav.includes('profile'))
  assert.ok(!nav.includes('employees'))
  assert.ok(!nav.includes('imports'))
  assert.ok(!nav.includes('reports'))
})
