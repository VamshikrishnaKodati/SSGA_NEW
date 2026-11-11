import fs from 'fs';

for (const file of ['src/pages/ImportCenter.jsx', 'src/pages/payroll/PayrollWorkspace.jsx', 'src/pages/payroll/StatementWorkspace.jsx']) {
  const s = fs.readFileSync(file, 'utf8');
  const set = new Set();
  for (const m of s.matchAll(/className=(?:"([^"]+)"|\{`([^`]+)`\}|\{'([^']+)'\})/g)) {
    let v = (m[1] || m[2] || m[3] || '').replace(/\$\{[^}]*\}/g, ' ');
    for (const c of v.split(/\s+/)) if (c) set.add(c);
  }
  console.log(`\n== ${file} ==`);
  console.log([...set].sort().join('\n'));
}
