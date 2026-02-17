# Build MindfulHome APK inside a Podman container
# Usage: .\build-in-container.ps1 [gradle-task]
# Example: .\build-in-container.ps1 assembleDebug

param(
    [string]$Task = "assembleDebug"
)

$ImageName = "mindfulhome-builder"
$ProjectRoot = $PSScriptRoot

# Build the container image if it doesn't exist
$imageExists = podman images -q $ImageName 2>$null
if (-not $imageExists) {
    Write-Host "Building container image '$ImageName'..."
    podman build -t $ImageName -f "$ProjectRoot\Containerfile" "$ProjectRoot"
}

Write-Host "Running 'gradle $Task' inside container..."
podman run --rm `
    -v "${ProjectRoot}:/project:Z" `
    $ImageName `
    gradle $Task
