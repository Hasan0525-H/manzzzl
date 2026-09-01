# Project API Contract

## Create Project
Input:
- userId
- projectName
- city
- floors

## Upload Plan
Input:
- projectId
- planFile

Output:
- analysisId
- status

## Processing Status
Returns:
- progress 0-100
- currentStep
- questionsRequired

## Render Result
Returns:
- exterior3DAsset
- realisticImages
- validationReport
