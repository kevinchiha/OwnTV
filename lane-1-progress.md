Task 1: done. No Interfaces deviations.
- task-1 fix round 1: done. Scrubber.host no longer returns a username when the login holds '/', '@', '?', '#', '\' or whitespace. No Interfaces deviation.
- Task 2: done. Deviations: added `LiveCheckin.onForeground()` (not in Interfaces) for minor gap transport-4; OUT OF REACH: `kevbox/KevBoxCoordinator.kt` needs `onForeground()` to call `LiveCheckin.onForeground()`, because the coordinator is not on the Task 2 Files list (until then the check-in runs at start, on sign-in and every 6 hours of awake time).
