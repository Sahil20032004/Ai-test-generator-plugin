# AI Test Generator - Quick Update Script
# This script rebuilds the plugin with the latest fixes

Write-Host "================================================" -ForegroundColor Cyan
Write-Host "  AI Test Generator - Quick Update Script" -ForegroundColor Cyan
Write-Host "================================================" -ForegroundColor Cyan
Write-Host ""

# Navigate to project directory
$projectPath = "C:\Users\w191635\StudioProjects\Ai-test-generator-plugin-1"
Write-Host "📁 Project Path: $projectPath" -ForegroundColor Yellow

if (-not (Test-Path $projectPath)) {
    Write-Host "❌ Error: Project directory not found!" -ForegroundColor Red
    Write-Host "   Please ensure the path is correct." -ForegroundColor Red
    exit 1
}

Set-Location $projectPath
Write-Host "✅ Changed directory to project" -ForegroundColor Green
Write-Host ""

# Clean and build
Write-Host "🔨 Building plugin with latest fixes..." -ForegroundColor Yellow
Write-Host "   - SSL certificate fix (PKIX error)" -ForegroundColor Gray
Write-Host "   - Action visibility improvements" -ForegroundColor Gray
Write-Host ""

try {
    & .\gradlew clean buildPlugin

    if ($LASTEXITCODE -eq 0) {
        Write-Host ""
        Write-Host "================================================" -ForegroundColor Green
        Write-Host "  ✅ BUILD SUCCESSFUL!" -ForegroundColor Green
        Write-Host "================================================" -ForegroundColor Green
        Write-Host ""

        $zipPath = "$projectPath\build\distributions\Ai-test-generator-plugin-0.0.1.zip"

        if (Test-Path $zipPath) {
            Write-Host "📦 Plugin Location:" -ForegroundColor Cyan
            Write-Host "   $zipPath" -ForegroundColor White
            Write-Host ""

            Write-Host "📋 Next Steps:" -ForegroundColor Cyan
            Write-Host "   1. Open your IDE (IntelliJ IDEA or Android Studio)" -ForegroundColor White
            Write-Host "   2. Go to: File → Settings → Plugins" -ForegroundColor White
            Write-Host "   3. UNINSTALL the old version first" -ForegroundColor Yellow
            Write-Host "   4. Restart the IDE" -ForegroundColor Yellow
            Write-Host "   5. Go to: File → Settings → Plugins" -ForegroundColor White
            Write-Host "   6. Click gear icon ⚙️ → Install Plugin from Disk..." -ForegroundColor White
            Write-Host "   7. Select the plugin file above" -ForegroundColor White
            Write-Host "   8. Restart the IDE again" -ForegroundColor Yellow
            Write-Host ""

            Write-Host "🔍 What's Fixed:" -ForegroundColor Cyan
            Write-Host "   ✅ SSL certificate error (PKIX path building)" -ForegroundColor Green
            Write-Host "   ✅ Action visibility in context menu" -ForegroundColor Green
            Write-Host "   ✅ Improved file type detection" -ForegroundColor Green
            Write-Host ""

            Write-Host "📚 Documentation:" -ForegroundColor Cyan
            Write-Host "   - Full Guide: PLUGIN_USAGE_GUIDE.md" -ForegroundColor White
            Write-Host "   - Quick Start: QUICK_START.md" -ForegroundColor White
            Write-Host "   - Troubleshooting: TROUBLESHOOTING_GUIDE.md" -ForegroundColor White
            Write-Host ""

            # Ask if user wants to open the folder
            Write-Host "Would you like to open the plugin folder? (Y/N): " -ForegroundColor Yellow -NoNewline
            $response = Read-Host

            if ($response -eq 'Y' -or $response -eq 'y') {
                explorer.exe "$projectPath\build\distributions"
                Write-Host "✅ Opened folder in Explorer" -ForegroundColor Green
            }

        } else {
            Write-Host "⚠️  Warning: Plugin file not found at expected location" -ForegroundColor Yellow
            Write-Host "   Expected: $zipPath" -ForegroundColor Gray
        }

    } else {
        Write-Host ""
        Write-Host "================================================" -ForegroundColor Red
        Write-Host "  ❌ BUILD FAILED!" -ForegroundColor Red
        Write-Host "================================================" -ForegroundColor Red
        Write-Host ""
        Write-Host "Please check the error messages above." -ForegroundColor Red
        exit 1
    }

} catch {
    Write-Host ""
    Write-Host "❌ Error during build: $_" -ForegroundColor Red
    exit 1
}

Write-Host ""
Write-Host "================================================" -ForegroundColor Cyan
Write-Host "  Script completed successfully!" -ForegroundColor Cyan
Write-Host "================================================" -ForegroundColor Cyan

