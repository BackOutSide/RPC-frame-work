param(
    [string]$BaseUrl = 'http://127.0.0.1:18080'
)

$ErrorActionPreference = 'Stop'
$BaseUrl = $BaseUrl.TrimEnd('/')

function Invoke-Api {
    param(
        [string]$Path,
        [string]$Method = 'GET',
        [string]$Token,
        [hashtable]$Body,
        [int]$ExpectedStatus = 200
    )

    $request = @{ Uri = "$BaseUrl$Path"; Method = $Method; TimeoutSec = 8 }
    if ($Token) { $request.Headers = @{ Authorization = "Bearer $Token" } }
    if ($null -ne $Body) {
        $request.ContentType = 'application/json'
        $request.Body = $Body | ConvertTo-Json -Compress
    }

    try {
        $response = Invoke-WebRequest @request -UseBasicParsing
    } catch {
        $status = $_.Exception.Response.StatusCode
        if ($ExpectedStatus -ge 400 -and [int]$status -eq $ExpectedStatus) { return }
        throw
    }

    if ([int]$response.StatusCode -ne $ExpectedStatus) {
        throw "$Method $Path expected HTTP $ExpectedStatus, got $($response.StatusCode)"
    }
    if ($response.Content) { $response.Content | ConvertFrom-Json }
}

$shop = Invoke-Api -Path '/api/shops/1'
if ($shop.id -ne 1) { throw 'Seed shop was not returned.' }
Invoke-Api -Path '/api/shops/99999999' -ExpectedStatus 404

$vouchers = @(Invoke-Api -Path '/api/shops/1/vouchers')
if (1 -notin $vouchers.id) { throw 'Seed voucher was not returned.' }

Invoke-Api -Path '/api/vouchers/1/seckill' -Method POST -ExpectedStatus 401
$alice = (Invoke-Api -Path '/api/sessions' -Method POST -Body @{ username = 'alice'; password = 'demo-alice' }).token
$bob = (Invoke-Api -Path '/api/sessions' -Method POST -Body @{ username = 'bob'; password = 'demo-bob' }).token
if (-not $alice -or -not $bob) { throw 'Demo login did not return tokens.' }

$first = Invoke-Api -Path '/api/vouchers/1/seckill' -Method POST -Token $alice -ExpectedStatus 202
$second = Invoke-Api -Path '/api/vouchers/1/seckill' -Method POST -Token $alice -ExpectedStatus 202
if ($first.status -ne 'ACCEPTED' -or -not $first.orderId -or $second.orderId -ne $first.orderId) {
    throw 'Repeated submission did not return the original accepted order ID.'
}

$orderPath = "/api/orders/$($first.orderId)"
Invoke-Api -Path $orderPath -Token $bob -ExpectedStatus 404
$deadline = [DateTime]::UtcNow.AddSeconds(20)
do {
    $order = Invoke-Api -Path $orderPath -Token $alice
    if ($order.status -eq 'CONFIRMED') { break }
    if ($order.status -ne 'PENDING') { throw "Unexpected order status $($order.status)" }
    Start-Sleep -Milliseconds 300
} while ([DateTime]::UtcNow -lt $deadline)

if ($order.status -ne 'CONFIRMED' -or $order.voucherId -ne 1 -or $order.orderId -ne $first.orderId) {
    throw 'Accepted order did not reach CONFIRMED.'
}

Write-Output "Dianping smoke passed. Shop=$($shop.id) Voucher=$($order.voucherId) Order=$($order.orderId) Status=$($order.status)"
