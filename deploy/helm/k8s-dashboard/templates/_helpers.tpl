{{- define "k8s-dashboard.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "k8s-dashboard.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{- define "k8s-dashboard.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{- define "k8s-dashboard.labels" -}}
helm.sh/chart: {{ include "k8s-dashboard.chart" . }}
{{ include "k8s-dashboard.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{- define "k8s-dashboard.selectorLabels" -}}
app.kubernetes.io/name: {{ include "k8s-dashboard.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app: {{ include "k8s-dashboard.name" . }}
{{- end }}

{{- define "k8s-dashboard.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "k8s-dashboard.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{- define "k8s-dashboard.secretName" -}}
{{- if .Values.secret.existingSecret }}
{{- .Values.secret.existingSecret }}
{{- else }}
{{- include "k8s-dashboard.fullname" . }}
{{- end }}
{{- end }}

{{- define "k8s-dashboard.exclusiveAccess" -}}
{{- if and .Values.ingress.enabled .Values.istio.enabled }}
{{- fail "Enable only one of ingress.enabled or istio.enabled" }}
{{- end }}
{{- end -}}

{{- define "k8s-dashboard.managedSecret" -}}
{{- if and (not .Values.secret.existingSecret) (or .Values.ldap.bindPassword .Values.ai.apiKey) -}}
true
{{- else -}}
false
{{- end -}}
{{- end }}
