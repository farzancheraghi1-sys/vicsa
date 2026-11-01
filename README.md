# AVACS clone: Profile dialog (client + backend)

## Contents

```
client/
  app/src/main/java/com/avacs/clone/profile/
    ProfileDialog.java       the dialog (same layout as the original)
    ProfileRepository.java   load profile, create profile after sign-up, admin verify
    UserProfile.java         data model
  app/src/main/res/layout/dialog_profile.xml
  app/src/main/res/drawable/ (12 files: backgrounds, close, pencil, star, blue check)
  gradle-snippets.txt        dependencies + how to call the dialog
backend/
  firestore.rules            security rules (paste into Firebase Console)
  firebase.json              only needed if you ever deploy with the CLI
```

## Client setup

1. Copy the files into your project, keeping the same folders.
2. If your package is not `com.avacs.clone`, change the `package` line and the `import com.avacs.clone.R;` line in the three Java files.
3. Add the dependencies from `gradle-snippets.txt` and put `google-services.json` in the `app/` folder.
4. Open the dialog: `new ProfileDialog(this, uid).show();`

## Backend setup (all from Firebase Console, no computer needed)

1. Authentication: enable Email/Password.
2. Firestore Database: create it (production mode).
3. Firestore, Rules tab: paste `backend/firestore.rules`, then Publish.
4. Firestore, Data tab: create collection `counters`, document ID `users`, field `next` of type number, value `10000000`. The first user gets ID 10000001.
5. Admin (optional): create collection `admins`, document ID = your user UID (copy it from the Authentication tab), with any field, for example `role` = `admin`.

## Verified blue check

Right cell of the Verify row shows only the blue check when `verified` is true. Otherwise the cell is empty.

- By hand: Firestore, `users`, open the document, set `verified` (boolean) to `true`.
- From the app: `new ProfileRepository().setVerified(uid, true, callback)`. Works only for UIDs listed in `admins`.
- Normal users cannot change `verified`, `userId` or `recordType` (blocked by the rules).

## Data model: `users/{uid}`

| Field | Type | Notes |
|---|---|---|
| nick | string | 1 to 30 characters |
| userId | number | issued from `counters/users` at sign-up |
| verified | boolean | admin only |
| hasPhoto | boolean | |
| albumsCount | number | |
| gender, avatar, nickColor, city, country, familyStatus | string | |
| recordType | string | "Unconfirmed" at sign-up, admin only afterwards |
| friendsCount, siblings | number | |
| createdAt | timestamp | |

## Notes

- E-mail is shown only on your own profile (read from Firebase Auth). On other people's profiles the row shows "Hidden". It is not stored in Firestore.
- Password row shows `********` only. Passwords live in Firebase Auth, never in Firestore.
- The Change button is visible only on your own profile and calls `Listener.onChangeClicked`. The edit screen itself is not part of this step.
- `albumsCount` and `friendsCount` are client-editable for now. Tighten the rules when the albums and friends features are built.
