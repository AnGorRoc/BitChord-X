// Checks that the dashboard's copy of the reply-variable rules (between the
// <fb-template> markers in telemetry/dashboard.html) gives the same results as the
// server, using the shared vectors:
//
//   node tools/check_template_port.js telemetry/dashboard.html feedback/testdata/template_vectors.json
const fs = require('fs');
const [html, vecPath] = process.argv.slice(2);
const src = fs.readFileSync(html, 'utf8');
const code = src.slice(src.indexOf('// <fb-template>'), src.indexOf('// </fb-template>'));
const { fbRender, fbLint } = new Function(code + '\nreturn { fbRender, fbLint };')();
const v = JSON.parse(fs.readFileSync(vecPath, 'utf8'));
let bad = 0;
for (const c of v.render) { const got = fbRender(c.template, c.vars); if (got !== c.out) { bad++; console.log('render', JSON.stringify(c.template), JSON.stringify(got), '!=', JSON.stringify(c.out)); } }
for (const c of v.lint) { const got = JSON.stringify(fbLint(c.template)); if (got !== JSON.stringify(c.problems)) { bad++; console.log('lint', c.template, got); } }
console.log(bad ? `${bad} FAILED` : `all ${v.render.length + v.lint.length} vectors pass`);
process.exit(bad ? 1 : 0);
