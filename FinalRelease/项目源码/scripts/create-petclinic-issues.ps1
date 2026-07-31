# Create 15 scenario Issues on the spring-petclinic fork.
# Requires: gh auth login
# Usage: .\scripts\create-petclinic-issues.ps1

param(
    [string]$Repo = "Yu-Liang-Yan/spring-petclinic"
)

$ErrorActionPreference = "Stop"
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

Write-Host "Target repo: $Repo" -ForegroundColor Cyan
gh auth status
if ($LASTEXITCODE -ne 0) {
    throw "gh is not authenticated. Run: gh auth login"
}

Write-Host "Enabling Issues on fork..." -ForegroundColor Cyan
gh api -X PATCH "repos/$Repo" -f has_issues=true | Out-Null

# Use ASCII-heavy bodies to avoid PowerShell encoding parse errors on Windows.
$issues = @(
    @{
        title = "[Bug] Owner details page NPE when lastName is null"
        body = @"
## Scenario
Bug analysis / fault localization (sufficient detail)

## Environment
- Spring Boot Petclinic
- JDK 17+
- H2 in-memory

## Steps to reproduce
1. Create an Owner with empty/null lastName via form tampering or API.
2. Open /owners/{id}.

## Expected
Page renders with a safe fallback or validation error.

## Actual
java.lang.NullPointerException
  at org.springframework.samples.petclinic.owner.OwnerController.showOwner(...)

## Impact
Owner detail page crashes for incomplete records.
"@
    },
    @{
        title = "[Bug] System error (insufficient info)"
        body = @"
Something broke.

(This issue intentionally lacks steps/logs. Used to test the insufficient-information analysis branch.)
"@
    },
    @{
        title = "[Bug] Pet visit date accepts future dates without validation"
        body = @"
## Scenario
Bug + validation gap

## Steps
1. Open an existing pet.
2. Add a visit with date = tomorrow + 30 days.
3. Submit.

## Expected
Validation error: visit date cannot be in the future.

## Actual
Visit is saved successfully.

## Related code
Likely Visit entity / visit form validators.
"@
    },
    @{
        title = "[Feature] Add phone number format validation for Owner"
        body = @"
## Scenario
Feature request / enhancement

## Proposal
Validate owner telephone as digits (optionally allow country code) before save.

## Acceptance criteria
- Invalid phone shows field error on form
- Valid phone saves normally
- Unit test covers invalid formats
"@
    },
    @{
        title = "[Question] How is the Owner-Pet relationship mapped in JPA?"
        body = @"
## Scenario
Documentation / Q&A knowledge retrieval

Please explain how OneToMany / ManyToOne is configured between Owner and Pet, and where cascade rules are defined.

Useful for testing RepoPilot smart Q&A against the domain model.
"@
    },
    @{
        title = "[Perf] /owners search becomes slow with 10k owners"
        body = @"
## Scenario
Performance concern

## Observation
findByLastName-style search degrades when dataset grows.

## Ask
- Is there an index on last_name?
- Should pagination be enforced server-side?

## Metrics (synthetic)
- 10k owners, lastName prefix query ~800ms on local H2 (rough)
"@
    },
    @{
        title = "[Security] Confirm CSRF protection on owner form POSTs"
        body = @"
## Scenario
Security review sample

Document expected Spring Security CSRF behavior for Petclinic browser form posts.

## Request
Confirm default CSRF protection is enabled for browser form posts.
"@
    },
    @{
        title = "[UI] Owner list table overflows on 1366x768"
        body = @"
## Scenario
Usability / compatibility (responsive)

## Steps
1. Open owners list at 1366x768.
2. Observe horizontal overflow / cramped actions.

## Expected
Readable layout without clipping primary actions.
"@
    },
    @{
        title = "[API] Return 404 problem+json when Owner id does not exist"
        body = @"
## Scenario
API error contract

## Expected
HTTP 404 with a clear body when /owners/{id} is unknown.

## Actual
Clarify current behavior (empty page vs exception vs redirect) and align with REST style if applicable.
"@
    },
    @{
        title = "[Data] Pet type seeding incomplete after fresh DB init"
        body = @"
## Scenario
Data initialization / deployment

## Steps
1. Wipe DB / use fresh profile.
2. Open create-pet form.

## Expected
Pet types (cat, dog, ...) available.

## Actual
Sometimes empty type dropdown after clean start (intermittent report for testing).
"@
    },
    @{
        title = "[Test] OwnerControllerTests fails intermittently on CI timezone"
        body = @"
## Scenario
Flaky test / CI reliability

## Symptom
Assertions on visit dates fail when CI runner TZ != UTC/Asia-Shanghai.

## Ask
Pin timezone in tests or use Instant/LocalDate carefully.
"@
    },
    @{
        title = "[Deps] Upgrade Spring Boot to latest supported patch"
        body = @"
## Scenario
Dependency maintenance

## Request
Evaluate upgrading Spring Boot parent to the latest patch in the current minor line.

## Notes
- Run full test suite
- Check release notes for security fixes
"@
    },
    @{
        title = "[Regression] Creating pet redirects to wrong owner after recent refactor"
        body = @"
## Scenario
Regression after change

## Steps
1. Open owner A.
2. Add pet.
3. Submit.

## Expected
Redirect back to owner A details.

## Actual
Sometimes lands on owner list / wrong id (for analysis + possible PR flow).

## Suspected area
OwnerController add-pet handler redirect attributes.
"@
    },
    @{
        title = "[Enhancement] Show pet visit count badge on owner detail"
        body = @"
## Scenario
Product enhancement (non-bug)

## Proposal
On owner detail, show a small badge with total visits across pets.

## Why
Helps demo UI feedback + knowledge graph symbol linking (Owner/Pet/Visit).
"@
    },
    @{
        title = "[Invalid] asdfasdf test spam"
        body = @"
Random text. No reproduction steps. No expected result.

(Used to test low-quality issue filtering / analysis rejection / ask-for-more-info.)
"@
    }
)

$created = New-Object System.Collections.Generic.List[string]
$i = 0
foreach ($issue in $issues) {
    $i++
    Write-Host ("[{0}/{1}] Creating: {2}" -f $i, $issues.Count, $issue.title) -ForegroundColor Yellow
    $tmp = Join-Path $env:TEMP ("petclinic-issue-{0}.md" -f $i)
    Set-Content -Path $tmp -Value $issue.body -Encoding utf8
    $url = gh issue create --repo $Repo --title $issue.title --body-file $tmp
    if ($LASTEXITCODE -ne 0 -or [string]::IsNullOrWhiteSpace($url)) {
        throw "Failed creating issue #$i"
    }
    $created.Add($url.Trim())
    Write-Host ("  -> {0}" -f $url.Trim()) -ForegroundColor Green
    Remove-Item $tmp -ErrorAction SilentlyContinue
    Start-Sleep -Milliseconds 500
}

Write-Host ""
Write-Host ("Created {0} issues:" -f $created.Count) -ForegroundColor Cyan
$created | ForEach-Object { Write-Host $_ }
