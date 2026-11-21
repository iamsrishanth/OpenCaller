[CmdletBinding()]
param(
    [string]$BindAddress = '127.0.0.1',
    [string]$AdvertisedAddress = '127.0.0.1'
)

$ErrorActionPreference = 'Stop'
$envFilePath = Join-Path (Split-Path -Parent $PSScriptRoot) '.env'

if (Test-Path -LiteralPath $envFilePath) {
    throw "A local .env already exists at '$envFilePath'. It was left unchanged."
}

foreach ($address in @($BindAddress, $AdvertisedAddress)) {
    if ($address -notmatch '^[A-Za-z0-9.-]+$') {
        throw 'Addresses must be IPv4 addresses or host names without spaces or shell characters.'
    }
}

$random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try {
    function New-LocalSecret([int]$ByteCount) {
        $bytes = New-Object byte[] $ByteCount
        $random.GetBytes($bytes)
        return [Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+', '-').Replace('/', '_')
    }

    function New-LocalPin {
        $bytes = New-Object byte[] 4
        $random.GetBytes($bytes)
        $number = [BitConverter]::ToUInt32($bytes, 0) % 100000000
        return '{0:D8}' -f $number
    }

    $lines = @(
        '# Generated locally by scripts/new-local-env.ps1; never commit this file.'
        "OPENCALLER_BIND_ADDRESS=$BindAddress"
        "OPENCALLER_ADVERTISED_ADDRESS=$AdvertisedAddress"
        "OPENCALLER_SIP_PASSWORD_1001=$(New-LocalSecret 32)"
        "OPENCALLER_SIP_PASSWORD_1002=$(New-LocalSecret 32)"
        "OPENCALLER_VOICEMAIL_PIN_1001=$(New-LocalPin)"
        "OPENCALLER_VOICEMAIL_PIN_1002=$(New-LocalPin)"
    )
    Set-Content -LiteralPath $envFilePath -Value $lines -Encoding ascii
}
finally {
    $random.Dispose()
}

Write-Output "Created local credentials at '$envFilePath'. Their values are not printed."
Write-Output 'The default bind and advertised addresses are localhost-only.'
