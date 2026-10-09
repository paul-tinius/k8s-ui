# Trusted CA certificates

The Dockerfile pulls the current LM combined CA bundle from
`https://crl.external.lmco.com/trust/pem/combined/` at build time and imports
every certificate in it into the JRE's trust store. This is required so
`ActiveDirectoryLdapAuthenticationProvider` can trust the LDAPS endpoint at
`acct01.us.lmco.com:3269` - without it, every LDAP login fails with an
`SSLHandshakeException` wrapped as an authentication error.

You can also drop any additional PEM-encoded CA certificate (`*.pem`) in this
directory before building the image to have it imported alongside the LM
bundle. Nothing here is picked up automatically beyond the build step; it's a
build-time input, not a secret checked into the repository. Keep real
certificates out of version control.
