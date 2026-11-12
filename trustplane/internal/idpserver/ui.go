package idpserver

// Pages served by the IdP (idp-rbac-cutover-plan.md P1.4, P1.8, P1.9). Rendered with
// html/template (contextual escaping); every form posts back to the IdP over the gateway.

import (
	"html/template"
	"net/http"
)

const pageCSS = `
:root{--bg:#f5f7fa;--card:#fff;--ink:#1d2433;--muted:#5b6475;--line:#d9dee7;--brand:#0b6e4f;--brand-ink:#fff;--err:#b42318;--errbg:#fef3f2;--ok:#05603a;--okbg:#ecfdf3}
@media (prefers-color-scheme:dark){:root{--bg:#0f141c;--card:#171e29;--ink:#e6eaf0;--muted:#9aa4b2;--line:#2a3442;--brand:#2fb383;--brand-ink:#08140f;--err:#fda29b;--errbg:#3a1a17;--ok:#75e0a7;--okbg:#0d2a1d}}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.5 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif}
main{max-width:26rem;margin:8vh auto;padding:0 16px}
.card{background:var(--card);border:1px solid var(--line);border-radius:12px;padding:28px}
h1{font-size:1.35rem;margin:0 0 4px}p.sub{color:var(--muted);margin:0 0 20px}
label{display:block;font-weight:600;font-size:.9rem;margin:14px 0 6px}
input,textarea{width:100%;padding:10px 12px;border:1px solid var(--line);border-radius:8px;background:var(--card);color:var(--ink);font:inherit}
input:focus,textarea:focus{outline:2px solid var(--brand);outline-offset:1px}
button{width:100%;margin-top:20px;padding:11px;border:0;border-radius:8px;background:var(--brand);color:var(--brand-ink);font:inherit;font-weight:600;cursor:pointer}
button.secondary{background:transparent;color:var(--ink);border:1px solid var(--line)}
.links{display:flex;justify-content:space-between;gap:12px;margin-top:16px;font-size:.9rem}
a{color:var(--brand)}
.msg{padding:10px 12px;border-radius:8px;margin:0 0 16px;font-size:.92rem}
.err{background:var(--errbg);color:var(--err)}.ok{background:var(--okbg);color:var(--ok)}
.or{text-align:center;color:var(--muted);margin:18px 0 0;font-size:.85rem}
ul.scopes{padding-left:18px}`

var pages = template.Must(template.New("base").Parse(`
{{define "head"}}<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><title>{{.Title}} · MiPay</title>
<style>` + pageCSS + `</style></head><body><main><div class="card">
<h1>{{.Title}}</h1>{{if .Sub}}<p class="sub">{{.Sub}}</p>{{end}}
{{if .Error}}<p class="msg err" role="alert">{{.Error}}</p>{{end}}{{if .Notice}}<p class="msg ok" role="status">{{.Notice}}</p>{{end}}{{end}}
{{define "foot"}}</div></main></body></html>{{end}}

{{define "login"}}{{template "head" .}}
<form method="POST" action="/login">
<input type="hidden" name="request_uri" value="{{.RequestURI}}">
<label for="u">Login name or phone</label><input id="u" name="username" autocomplete="username" required autofocus>
<label for="p">Password</label><input id="p" name="password" type="password" autocomplete="current-password" required>
<button>Sign in</button></form>
{{if .OTP}}<p class="or">or</p>
<form method="POST" action="/login/otp/start"><input type="hidden" name="request_uri" value="{{.RequestURI}}">
<label for="ph">Phone number</label><input id="ph" name="phone" type="tel" autocomplete="tel" placeholder="+264 81 123 4567">
<button class="secondary">Text me a sign-in code</button></form>{{end}}
<div class="links">{{if .Forgot}}<a href="/account/forgot?request_uri={{.RequestURI}}">Forgot password?</a>{{else}}<span></span>{{end}}
<a href="/account/help?request_uri={{.RequestURI}}">Can't get in?</a></div>
{{template "foot"}}{{end}}

{{define "otp"}}{{template "head" .}}
<form method="POST" action="/login/otp/verify">
<input type="hidden" name="request_uri" value="{{.RequestURI}}"><input type="hidden" name="challenge" value="{{.Challenge}}">
<label for="c">6-digit code</label><input id="c" name="code" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" autocomplete="one-time-code" required autofocus>
<button>Verify and sign in</button></form>
<div class="links"><a href="/authorize?client_id={{.ClientID}}&amp;request_uri={{.RequestURI}}">Use a password instead</a></div>
{{template "foot"}}{{end}}

{{define "mfa"}}{{template "head" .}}
{{if .Secret}}<p class="sub">Add this secret to an authenticator app, then enter its current six-digit code:</p>
<p><code>{{.Secret}}</code></p>{{end}}
<form method="POST" action="/login/mfa/verify">
<input type="hidden" name="request_uri" value="{{.RequestURI}}"><input type="hidden" name="challenge" value="{{.Challenge}}">
<label for="mfa-code">{{if .Secret}}Authenticator code{{else}}Security code{{end}}</label>
<input id="mfa-code" name="code" inputmode="numeric" pattern="[0-9]{6}" maxlength="6" autocomplete="one-time-code" required autofocus>
<button>Verify and continue</button></form>
{{if .CanSwitch}}<form method="POST" action="/login/mfa/switch">
<input type="hidden" name="request_uri" value="{{.RequestURI}}"><input type="hidden" name="challenge" value="{{.Challenge}}">
<button class="secondary">Set up an authenticator instead</button></form>{{end}}
{{template "foot"}}{{end}}

{{define "consent"}}{{template "head" .}}
<p><b>{{.ClientName}}</b> requests access on your behalf:</p><ul class="scopes">{{range .Scopes}}<li><code>{{.Name}}</code>: {{.Desc}}</li>{{end}}</ul>
<form method="POST" action="/consent"><input type="hidden" name="request_uri" value="{{.RequestURI}}"><input type="hidden" name="csrf" value="{{.CSRF}}">
<button name="decision" value="allow">Allow</button><button class="secondary" name="decision" value="deny">Deny</button></form>
{{template "foot"}}{{end}}

{{define "setpw"}}{{template "head" .}}
{{if .Token}}<form method="POST" action="/account/setup"><input type="hidden" name="token" value="{{.Token}}">
<label for="n">New password</label><input id="n" name="password" type="password" autocomplete="new-password" minlength="12" required autofocus>
<label for="c">Repeat the password</label><input id="c" name="confirm" type="password" autocomplete="new-password" minlength="12" required>
<p class="sub" style="margin-top:12px">At least 12 characters. Not your login name, and not your phone number.</p>
<button>Save password</button></form>{{end}}
{{template "foot"}}{{end}}

{{define "forgot"}}{{template "head" .}}
{{if not .Notice}}<form method="POST" action="/account/forgot"><input type="hidden" name="request_uri" value="{{.RequestURI}}">
<label for="u">Login name or phone</label><input id="u" name="login" autocomplete="username" required autofocus>
<button>Send me a reset link</button></form>{{end}}
{{if .RequestURI}}<div class="links"><a href="/authorize?client_id={{.ClientID}}&amp;request_uri={{.RequestURI}}">Back to sign in</a></div>{{end}}
{{template "foot"}}{{end}}

{{define "help"}}{{template "head" .}}
{{if not .Notice}}<form method="POST" action="/account/help"><input type="hidden" name="request_uri" value="{{.RequestURI}}">
<label for="u">Login name or phone</label><input id="u" name="login" autocomplete="username" required>
<label for="m">What happened? (optional)</label><textarea id="m" name="message" rows="3" maxlength="500"></textarea>
<button>Ask for help</button></form>{{end}}
{{if .RequestURI}}<div class="links"><a href="/authorize?client_id={{.ClientID}}&amp;request_uri={{.RequestURI}}">Back to sign in</a></div>{{end}}
{{template "foot"}}{{end}}

{{define "message"}}{{template "head" .}}{{template "foot"}}{{end}}
`))

type scopeView struct{ Name, Desc string }

type pageData struct {
	Title, Sub, Error, Notice string
	RequestURI, ClientID      string
	OTP, Forgot               bool
	Challenge, Token, CSRF    string
	Secret                    string
	CanSwitch                 bool
	ClientName                string
	Scopes                    []scopeView
}

func render(w http.ResponseWriter, status int, name string, d pageData) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Header().Set("X-Frame-Options", "DENY")
	w.Header().Set("Content-Security-Policy", "default-src 'none'; style-src 'unsafe-inline'; frame-ancestors 'none'; base-uri 'none'")
	w.Header().Set("Referrer-Policy", "no-referrer")
	w.WriteHeader(status)
	_ = pages.ExecuteTemplate(w, name, d)
}
