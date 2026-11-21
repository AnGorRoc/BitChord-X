package feedback

import (
	"encoding/json"
	"os"
	"reflect"
	"testing"
)

type templateVectors struct {
	Render []struct {
		Template string            `json:"template"`
		Vars     map[string]string `json:"vars"`
		Out      string            `json:"out"`
	} `json:"render"`
	Lint []struct {
		Template string   `json:"template"`
		Problems []string `json:"problems"`
	} `json:"lint"`
}

func loadVectors(t *testing.T) templateVectors {
	t.Helper()
	raw, err := os.ReadFile("testdata/template_vectors.json")
	if err != nil {
		t.Fatal(err)
	}
	var v templateVectors
	if err := json.Unmarshal(raw, &v); err != nil {
		t.Fatal(err)
	}
	return v
}

func TestRenderTemplateVectors(t *testing.T) {
	for _, c := range loadVectors(t).Render {
		if got := RenderTemplate(c.Template, c.Vars); got != c.Out {
			t.Errorf("%q with %v:\n got  %q\n want %q", c.Template, c.Vars, got, c.Out)
		}
	}
}

func TestLintTemplateVectors(t *testing.T) {
	for _, c := range loadVectors(t).Lint {
		if got := LintTemplate(c.Template); !reflect.DeepEqual(got, c.Problems) {
			t.Errorf("%q: got %v want %v", c.Template, got, c.Problems)
		}
	}
}

func TestDefaultTemplatesLintClean(t *testing.T) {
	for _, tp := range defaultTemplates {
		if p := LintTemplate(tp.Body); len(p) != 0 {
			t.Errorf("%s: %v", tp.Name, p)
		}
	}
}
