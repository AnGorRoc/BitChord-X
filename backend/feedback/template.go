package feedback

import (
	"regexp"
	"sort"
	"strings"
)

// Reply variables.
//
// The developer writes "Hola {{nombre}}, gracias" and the server stores exactly that. The app
// fills the variables from what it knows locally (the name of the account signed in on it, its
// version), so the server never learns the person's name. The same rules are implemented in the
// dashboard (for its preview) and in the app; testdata/template_vectors.json is the shared
// definition all three are tested against.
//
// Syntax: {{name}} or {{name|fallback}}. Names are letters and underscores; spaces inside the
// braces are allowed. Anything that does not parse is left as written.
//
// When a variable ends up empty (no value and no fallback), the text around it is tidied so a
// missing name does not leave a gap: "Hola {{nombre}}, gracias" becomes "Hola, gracias" and
// "¡Listo, {{nombre}}!" becomes "¡Listo!".

// Variables the app knows how to fill.
var TemplateVars = []string{"nombre", "version", "plataforma", "ticket", "titulo"}

var (
	templateToken = regexp.MustCompile(`\{\{\s*([A-Za-z_]{1,24})\s*(?:\|([^{}\n]{0,60}))?\}\}`)
	knownVars     = func() map[string]bool {
		m := map[string]bool{}
		for _, v := range TemplateVars {
			m[v] = true
		}
		return m
	}()
)

// RenderTemplate fills body's variables from vars (missing keys count as empty).
func RenderTemplate(body string, vars map[string]string) string {
	var out strings.Builder
	rest := body
	skipSpace := false
	for {
		loc := templateToken.FindStringSubmatchIndex(rest)
		if loc == nil {
			out.WriteString(dropLeadingSpace(rest, skipSpace))
			return out.String()
		}
		out.WriteString(dropLeadingSpace(rest[:loc[0]], skipSpace))
		skipSpace = false
		name := strings.ToLower(rest[loc[2]:loc[3]])
		value := strings.TrimSpace(vars[name])
		if value == "" && loc[4] >= 0 {
			value = strings.TrimSpace(rest[loc[4]:loc[5]])
		}
		rest = rest[loc[1]:]
		if value != "" {
			out.WriteString(value)
			continue
		}
		// Empty: close the gap it leaves.
		s := out.String()
		if rest == "" || strings.ContainsRune(",.!?;:)", rune(rest[0])) {
			switch {
			case strings.HasSuffix(s, ", "):
				s = s[:len(s)-2]
			case strings.HasSuffix(s, " "):
				s = s[:len(s)-1]
			}
			out.Reset()
			out.WriteString(s)
		} else if rest[0] == ' ' && (s == "" || strings.HasSuffix(s, " ") || strings.HasSuffix(s, "\n")) {
			skipSpace = true
		}
	}
}

func dropLeadingSpace(s string, drop bool) string {
	if drop && strings.HasPrefix(s, " ") {
		return s[1:]
	}
	return s
}

// LintTemplate lists what is wrong with body, as codes: "unknown:<name>" for a variable the app
// cannot fill, "malformed" for a "{{" that does not start a variable. Empty means it is fine.
func LintTemplate(body string) []string {
	problems := map[string]bool{}
	for _, m := range templateToken.FindAllStringSubmatch(body, -1) {
		if name := strings.ToLower(m[1]); !knownVars[name] {
			problems["unknown:"+name] = true
		}
	}
	if strings.Count(templateToken.ReplaceAllString(body, ""), "{{") > 0 {
		problems["malformed"] = true
	}
	out := make([]string, 0, len(problems))
	for p := range problems {
		out = append(out, p)
	}
	sort.Strings(out)
	return out
}
