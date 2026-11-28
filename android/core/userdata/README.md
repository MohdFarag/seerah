# :core:userdata

Local storage for the explore session. See the [v1 user data spec](../../../docs/design/v1-userdata-spec.md).

- `UserDataRepository.observeSession()` emits the saved time, focus, and bookmark. A missing file gives an empty session. A failed read emits one typed result and ends the collection.
- `UserDataRepository.updateSession()` applies a pure transform to the latest saved session in one transaction. Update time and focus together when they describe one action. A successful result contains the committed session.
- DataStore keeps a versioned JSON file at `filesDir/datastore/explore-session.json`, outside the replaceable content directory. Unreadable and newer files are preserved.
- The app shell will use the Hilt binding to load this session, resolve references against current content, and offer to resume a valid bookmark.

## Test

Run from `android/`.

```sh
./gradlew :core:userdata:testDebugUnitTest
./gradlew :core:userdata:connectedDebugAndroidTest
```
