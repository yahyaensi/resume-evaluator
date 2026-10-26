param(
    [switch]$SkipBuild
)

$ErrorActionPreference = "Stop"

$root = $PSScriptRoot
$targetDir = Join-Path $root "target"
$inputDir = Join-Path $targetDir "jpackage-input"
$appImageParent = Join-Path $targetDir "windows-installer-build"
$appImageDir = Join-Path $appImageParent "ResumeEvaluator"
$buildDir = Join-Path $targetDir "wix-build"
$packageDir = Join-Path $targetDir "windows-installer"
$pomPath = Join-Path $root "pom.xml"
[xml]$pom = Get-Content -LiteralPath $pomPath
$appVersion = $pom.SelectSingleNode("/*[local-name()='project']/*[local-name()='version']").InnerText
$jarName = "resume-evaluator-$appVersion.jar"
$installerPath = Join-Path $packageDir "ResumeEvaluator-$appVersion.msi"
$legacyInstallerPath = Join-Path $packageDir "ResumeEvaluator-$appVersion.exe"

function Find-ComponentForDirectory($directory, $document, $namespaceManager) {
    $directoryId = $directory.GetAttribute("Id")
    $component = $document.SelectSingleNode(
        "//wi:Component[@Directory='$directoryId']", $namespaceManager)
    if ($null -ne $component) {
        return $component
    }

    foreach ($childDirectory in $directory.SelectNodes("wi:Directory", $namespaceManager)) {
        $component = Find-ComponentForDirectory $childDirectory $document $namespaceManager
        if ($null -ne $component) {
            return $component
        }
    }
    return $null
}

Push-Location $root
try {
    foreach ($tool in @("heat.exe", "candle.exe", "light.exe")) {
        if (-not (Get-Command $tool -ErrorAction SilentlyContinue)) {
            throw "WiX Toolset 3.0+ est requis (heat.exe, candle.exe et light.exe). Ajoutez son dossier bin au PATH."
        }
    }

    if (-not $SkipBuild) {
        & mvn package
        if ($LASTEXITCODE -ne 0) {
            throw "La construction Maven a échoué."
        }
    }

    foreach ($path in @($appImageDir, $buildDir, $installerPath, $legacyInstallerPath)) {
        if (Test-Path -LiteralPath $path) {
            Remove-Item -LiteralPath $path -Recurse -Force
        }
    }

    New-Item -ItemType Directory -Path $inputDir, $appImageParent, $buildDir, $packageDir -Force | Out-Null
    Copy-Item -LiteralPath (Join-Path $targetDir $jarName) `
        -Destination $inputDir

    & jpackage `
        --type app-image `
        --name ResumeEvaluator `
        --app-version $appVersion `
        --vendor "Resume Evaluator" `
        --input $inputDir `
        --main-jar $jarName `
        --main-class com.hr.App `
        --icon (Join-Path $root "src\main\resources\logo.ico") `
        --dest $appImageParent

    if ($LASTEXITCODE -ne 0) {
        throw "La création de l'application Windows a échoué."
    }

    $harvestedWxs = Join-Path $buildDir "ApplicationFiles.wxs"
    & heat.exe dir $appImageDir `
        -cg ApplicationFiles `
        -dr INSTALLFOLDER `
        -ag `
        -scom `
        -sreg `
        -srd `
        -var var.SourceDir `
        -out $harvestedWxs

    if ($LASTEXITCODE -ne 0) {
        throw "La collecte des fichiers de l'application a échoué."
    }

    [xml]$harvestedDocument = Get-Content -LiteralPath $harvestedWxs -Raw
    $namespaceManager = New-Object System.Xml.XmlNamespaceManager($harvestedDocument.NameTable)
    $namespaceManager.AddNamespace("wi", $harvestedDocument.DocumentElement.NamespaceURI)
    $md5 = [System.Security.Cryptography.MD5]::Create()
    foreach ($component in $harvestedDocument.SelectNodes("//wi:Component", $namespaceManager)) {
        $fileKeyPath = $component.SelectSingleNode("wi:File[@KeyPath='yes']", $namespaceManager)
        if ($null -ne $fileKeyPath) {
            $componentId = $component.GetAttribute("Id")
            $guidBytes = $md5.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($componentId))
            $component.SetAttribute("Guid", ([System.Guid]::new($guidBytes)).ToString("B"))
            $fileKeyPath.RemoveAttribute("KeyPath")
            $registryValue = $harvestedDocument.CreateElement(
                "RegistryValue", $harvestedDocument.DocumentElement.NamespaceURI)
            $registryValue.SetAttribute("Root", "HKCU")
            $registryValue.SetAttribute(
                "Key", "Software\Resume Evaluator\Components\$componentId")
            $registryValue.SetAttribute("Name", "Installed")
            $registryValue.SetAttribute("Type", "integer")
            $registryValue.SetAttribute("Value", "1")
            $registryValue.SetAttribute("KeyPath", "yes")
            $component.AppendChild($registryValue) | Out-Null
        }
    }
    foreach ($directory in $harvestedDocument.SelectNodes("//wi:Directory", $namespaceManager)) {
        $component = Find-ComponentForDirectory $directory $harvestedDocument $namespaceManager
        if ($null -eq $component) {
            $directoryId = $directory.GetAttribute("Id")
            $componentId = "cmpRemove_$directoryId"
            $guidBytes = $md5.ComputeHash([System.Text.Encoding]::UTF8.GetBytes($componentId))
            $component = $harvestedDocument.CreateElement(
                "Component", $harvestedDocument.DocumentElement.NamespaceURI)
            $component.SetAttribute("Id", $componentId)
            $component.SetAttribute("Guid", ([System.Guid]::new($guidBytes)).ToString("B"))
            $component.SetAttribute("Directory", $directoryId)
            $registryValue = $harvestedDocument.CreateElement(
                "RegistryValue", $harvestedDocument.DocumentElement.NamespaceURI)
            $registryValue.SetAttribute("Root", "HKCU")
            $registryValue.SetAttribute("Key", "Software\Resume Evaluator\Directories\$directoryId")
            $registryValue.SetAttribute("Name", "Installed")
            $registryValue.SetAttribute("Type", "integer")
            $registryValue.SetAttribute("Value", "1")
            $registryValue.SetAttribute("KeyPath", "yes")
            $component.AppendChild($registryValue) | Out-Null
            $componentGroup = $harvestedDocument.SelectSingleNode(
                "//wi:ComponentGroup[@Id='ApplicationFiles']", $namespaceManager)
            $componentGroup.AppendChild($component) | Out-Null
        }
        $removeFolder = $harvestedDocument.CreateElement(
            "RemoveFolder", $harvestedDocument.DocumentElement.NamespaceURI)
        $removeFolder.SetAttribute("Id", "Remove_$($directory.GetAttribute('Id'))")
        $removeFolder.SetAttribute("Directory", $directory.GetAttribute("Id"))
        $removeFolder.SetAttribute("On", "uninstall")
        $component.AppendChild($removeFolder) | Out-Null
    }
    $md5.Dispose()
    $harvestedDocument.Save($harvestedWxs)

    $productWxs = Join-Path $root "installer\Product.wxs"
    $productObject = Join-Path $buildDir "Product.wixobj"
    $filesObject = Join-Path $buildDir "ApplicationFiles.wixobj"
    & candle.exe -nologo -ext WixUIExtension "-dSourceDir=$appImageDir" "-dProjectDir=$root" `
        "-dProductVersion=$appVersion" `
        -out $productObject $productWxs
    if ($LASTEXITCODE -ne 0) {
        throw "La compilation de la configuration MSI a échoué."
    }

    & candle.exe -nologo "-dSourceDir=$appImageDir" `
        -out $filesObject $harvestedWxs
    if ($LASTEXITCODE -ne 0) {
        throw "La compilation des fichiers de l'application a échoué."
    }

    & light.exe -nologo -ext WixUIExtension -out $installerPath `
        $productObject $filesObject
    if ($LASTEXITCODE -ne 0) {
        throw "La création du package MSI a échoué."
    }

    Write-Output "Installateur MSI créé : $installerPath"
}
finally {
    Pop-Location
}
