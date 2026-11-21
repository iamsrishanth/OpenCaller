#!/bin/sh
set -eu

: "${OPENCALLER_SIP_PASSWORD_1001:?Generate dev/asterisk/.env with scripts/new-local-env.ps1}"
: "${OPENCALLER_SIP_PASSWORD_1002:?Generate dev/asterisk/.env with scripts/new-local-env.ps1}"
: "${OPENCALLER_VOICEMAIL_PIN_1001:?Generate dev/asterisk/.env with scripts/new-local-env.ps1}"
: "${OPENCALLER_VOICEMAIL_PIN_1002:?Generate dev/asterisk/.env with scripts/new-local-env.ps1}"
: "${OPENCALLER_ADVERTISED_ADDRESS:?Set OPENCALLER_ADVERTISED_ADDRESS in dev/asterisk/.env}"

# Install the checked-in static configuration into the container's writable
# config directory, then render only the two templates that contain secrets.
for config_file in /opt/opencaller-asterisk-config/*; do
    case "$config_file" in
        *.template) continue ;;
    esac
    cp "$config_file" /etc/asterisk/
done

# Keep Asterisk's ${DIALSTATUS} and other dialplan expressions intact. Only
# expand the explicitly listed environment variables that contain lab secrets.
envsubst '${OPENCALLER_ADVERTISED_ADDRESS} ${OPENCALLER_SIP_PASSWORD_1001} ${OPENCALLER_SIP_PASSWORD_1002}' \
    < /opt/opencaller-asterisk-config/pjsip.conf.template \
    > /etc/asterisk/pjsip.conf
envsubst '${OPENCALLER_VOICEMAIL_PIN_1001} ${OPENCALLER_VOICEMAIL_PIN_1002}' \
    < /opt/opencaller-asterisk-config/voicemail.conf.template \
    > /etc/asterisk/voicemail.conf

chmod 0640 /etc/asterisk/pjsip.conf /etc/asterisk/voicemail.conf

exec "$@"
