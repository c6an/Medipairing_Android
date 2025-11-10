Pill Recognition — Accuracy & Performance Tips

What’s implemented in the app
- Circular viewfinder overlay: guides users to center only the pill, reducing background/box confusion.
- ROI‑based OCR with density filter: we only read text inside the center ROI and ignore frames with too much text (likely a package/leaflet).
- Stability debouncing: requires several consecutive, identical reads before querying the server to avoid flicker.
- Low‑light hint + center AF/AE: we hint torch usage when too dark and bias autofocus/exposure to the center.
- Optional on‑device models: if you drop models in `app/src/main/assets/models/`, the app uses them:
  - `pill_detector.tflite` (ObjectDetector): gates non‑pill frames (rejects labels like box/bottle).
  - `pill_classifier.tflite` (ImageClassifier): shows top prediction text in the overlay.

How to get better results
- Lighting: diffuse, bright light is best. Avoid glare; tilt the phone to reduce reflections.
- Background: plain background with strong contrast to the pill. Avoid printed surfaces or boxes in ROI.
- Distance: fill most of the circular window with the pill, but keep the imprint in focus and sharp.
- Orientation: rotate so the imprint is horizontal; OCR is more stable.
- Motion: hold for ~1 second after text first appears to let the stability filter confirm.

Tunable parameters (see MedipairingFragment)
- `REQUIRED_STABLE_HITS`: increase to be stricter (less false positives, slower response).
- `FRAME_SKIP`: increase to reduce CPU usage; decrease for faster response.
- `MAX_TEXT_COVERAGE`: lower to reject more packaging‑like frames.
- Detector gate: adjust allowed labels/threshold to match your custom model.

Custom model tips
- Use an object detector that outputs only one class ("pill"). Train with crops of only pills (no boxes).
- If using a classifier, train on center‑cropped pill images and keep label set compact.
- Convert to TFLite with float or INT8; test speed on target devices. Place TFLite under `app/src/main/assets/models/`.

Interactions (금기) integration
- Endpoints
  - Add flow (must use): `GET /interactions/check-against-user?code=...` with `Authorization: Bearer <JWT>`
    - Response: `{ query_pill_id, query_pill_name, conflicts:[...], highest_severity, can_add }`
    - Decision: `can_add == false` → block add and show conflicts. `can_add == true` → proceed add.
  - Pill summary (optional, non‑personal): `GET /interactions/for-pill?code=...`
    - Use on detail screens to show general risk; do NOT use for add decision.

- Where it’s wired in the app
  - Scan (MedipairingFragment)
    - When a stable imprint code is recognized, the app resolves the pill and then calls `/interactions/check-against-user`.
    - If `can_add == false`, navigates to `MedicineerrorFragment` (금기 안내). Otherwise goes to `MedicinegoodFragment`.
    - If not logged in (no JWT), it skips the user check and shows the normal detail; the actual Add button still re‑checks.
  - Detail “Add to MyPage” (MedicineinfoFragment, MedicinegoodFragment)
    - Before POST `/me/pills`, calls `/interactions/check-against-user` and blocks when `can_add == false`.
  - Search/Upload add (MedicineUploadFragment)
    - Same as above: pre‑check with `/interactions/check-against-user` before adding.

- UI behaviors on block
  - Show a short toast explaining “내 보관함과 복용 금기입니다”.
  - From scan, navigate to `MedicineerrorFragment` to present an error view; from add buttons, return to MyPage.

- Errors
  - 401 Unauthorized: prompt login and skip the add. 404: code not found → ask user to rescan.
