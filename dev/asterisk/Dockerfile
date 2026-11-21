FROM debian:bookworm-slim

ARG ASTERISK_VERSION=22.12.0
ARG ASTERISK_SHA256=f8b2ba98aac6c4ff3376bc0a2868bea6ed577ca684efb7ec739ae6e8ce6e35e2

ENV DEBIAN_FRONTEND=noninteractive

# Asterisk is built from its upstream source release. Its install_prereq
# script selects the dependencies for the Debian release.
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        build-essential \
        ca-certificates \
        curl \
        file \
        git \
        wget

RUN curl --fail --location --silent --show-error \
        "https://downloads.asterisk.org/pub/telephony/asterisk/releases/asterisk-${ASTERISK_VERSION}.tar.gz" \
        --output /tmp/asterisk.tar.gz \
    && echo "${ASTERISK_SHA256}  /tmp/asterisk.tar.gz" | sha256sum --check --status \
    && mkdir -p /usr/src/asterisk \
    && tar --extract --gzip --file /tmp/asterisk.tar.gz --directory /usr/src/asterisk --strip-components=1 \
    && rm /tmp/asterisk.tar.gz \
    && cd /usr/src/asterisk \
    && ./contrib/scripts/install_prereq install \
    && ./configure --with-pjproject-bundled \
    && make menuselect.makeopts \
    && menuselect/menuselect --enable chan_pjsip --enable app_voicemail --enable format_wav \
        --enable CORE-SOUNDS-EN-WAV menuselect.makeopts \
    && make -j"$(nproc)" \
    && make install \
    && cd / \
    && rm -rf /usr/src/asterisk \
    && apt-get clean \
    && rm -rf /var/lib/apt/lists/*

RUN apt-get update \
    && apt-get install -y --no-install-recommends gettext-base \
    && groupadd --system --gid 10001 asterisk \
    && useradd --system --uid 10001 --gid asterisk --home-dir /var/lib/asterisk \
        --no-create-home --shell /usr/sbin/nologin asterisk \
    && mkdir -p /opt/opencaller-asterisk-config /var/spool/asterisk/voicemail \
        /var/lib/asterisk /var/log/asterisk /run/asterisk \
    && chown -R asterisk:asterisk /etc/asterisk /opt/opencaller-asterisk-config \
        /var/spool/asterisk /var/lib/asterisk /var/log/asterisk /run/asterisk \
    && rm -rf /var/lib/apt/lists/*

COPY config/ /opt/opencaller-asterisk-config/
COPY entrypoint.sh /usr/local/bin/opencaller-asterisk-entrypoint
RUN chmod 0555 /usr/local/bin/opencaller-asterisk-entrypoint

USER 10001:10001
EXPOSE 5060/udp 10000-10100/udp
ENTRYPOINT ["/usr/local/bin/opencaller-asterisk-entrypoint"]
CMD ["/usr/sbin/asterisk", "-f", "-vv"]
