param(
    [Parameter(Mandatory = $true)]
    [ValidateSet('server', 'client')]
    [string]$Role,

    [Parameter(Mandatory = $true)]
    [string]$AgentJar,

    [string]$MavenSettings,

    [switch]$Build
)

$ErrorActionPreference = 'Stop'
$agentPath = (Resolve-Path -LiteralPath $AgentJar).Path
$repoRoot = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..\..')).Path
$module = "examples/hello/$Role"
$moduleRoot = Join-Path $repoRoot $module
$classPathFile = Join-Path $moduleRoot 'target/observe-netty-classpath.txt'
$mainClass = if ($Role -eq 'server') { 'NettyServerMain' } else { 'io.github.backoutside.rivet.NettyClientMain' }
$originalLocation = Get-Location
$mavenArgs = @()
if ($MavenSettings) {
    $mavenArgs = @('-s', (Resolve-Path -LiteralPath $MavenSettings).Path)
}

try {
    Set-Location -LiteralPath $repoRoot
    if ($Build) {
        & mvn @mavenArgs -q -pl 'examples/hello/server,examples/hello/client' -am -DskipTests install
        if ($LASTEXITCODE -ne 0) { throw 'Maven build failed.' }
    }

    $classes = Join-Path $moduleRoot 'target/classes'
    if (-not (Test-Path -LiteralPath $classes)) {
        throw "Missing $classes. Run the server role with -Build first."
    }
    & mvn @mavenArgs -q -pl $module dependency:build-classpath "-Dmdep.outputFile=$classPathFile"
    if ($LASTEXITCODE -ne 0) { throw 'Could not resolve the example classpath.' }
    $dependencies = (Get-Content -LiteralPath $classPathFile -Raw).Trim()
    if (-not $dependencies) { throw 'The resolved example classpath is empty.' }

    $javaArgs = @(
        "-javaagent:$agentPath",
        "-Dotel.service.name=rivet-hello-$Role",
        '-Dotel.traces.exporter=console',
        '-Dotel.metrics.exporter=none',
        '-Dotel.logs.exporter=none',
        '-Dotel.traces.sampler=parentbased_always_on',
        '-Dotel.instrumentation.common.default-enabled=false',
        '-Dotel.instrumentation.opentelemetry-api.enabled=true',
        '-Dotel.instrumentation.opentelemetry-instrumentation-annotations.enabled=true',
        '-cp',
        "$classes$([IO.Path]::PathSeparator)$dependencies",
        $mainClass
    )
    & java @javaArgs
    if ($LASTEXITCODE -ne 0) { throw "Java $Role process exited with code $LASTEXITCODE." }
} finally {
    Set-Location -LiteralPath $originalLocation
}
