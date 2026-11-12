# UTEChat: backend cho nhom ban thu nghiem

Dung Firebase Spark cho Auth/Firestore/FCM, Render Free cho Node API, Supabase Free cho kho anh **private**, Agora Free cho RTC. Khong bat Blaze, Cloud Run, Cloud Functions hay tai nguyen tra phi trong huong dan nay.

## Render

- Repo: `https://github.com/akedam/UTECHAT`, nhanh da chua thay doi backend.
- Web Service, Node, Singapore, **Free ($0)**; Root Directory `server/call-token`.
- Build `npm ci --omit=dev`, Start `npm start`, Health Check `/healthz`.
- Bien moi truong: `FIREBASE_PROJECT_ID=utechat-ab9d8`, `AGORA_APP_ID`, `AGORA_APP_CERTIFICATE`, `SUPABASE_URL`, `SUPABASE_SECRET_KEY`, `SUPABASE_STORAGE_BUCKET=utechat-media`, `GOOGLE_APPLICATION_CREDENTIALS=/etc/secrets/firebase-admin.json`.
- Them Secret File `firebase-admin.json` tu file service account giu ngoai repo. Khong dan noi dung vao GitHub, chat, screenshot hay APK.
- Supabase tao bucket `utechat-media`, **private**, toi da 8 MiB/object, MIME JPEG/PNG/WebP. Service-role key chi luu tren backend; Android khong can tai khoan Supabase.

Render Free ngu sau 15 phut khong truy cap va co the mat khoang mot phut de thuc day. App cho `/healthz` san sang **truoc** khi tao cuoc goi 45 giay, va HTTP timeout cho cold start. Khong ping gia de tranh ngu. Khong them the thanh toan neu muon vuot quota thi tam dung thay vi bi tinh them phi.

## APK de chia se

Sau khi URL that san sang, tai repo chay:

```powershell
powershell.exe -NoProfile -ExecutionPolicy RemoteSigned -File .\scripts\build-tester.ps1 -BackendUrl https://TEN-DICH-VU.onrender.com
```

Script kiem tra backend da bat kho anh, giu App ID local, doi sang HTTPS that, bat media backend va tao signing key mot lan. APK: `build/UTEChat-test.apk` (Android 8 tro len, arm64). Sao luu rieng `.private/utechat-tester.jks` va `tester-signing.local.properties` de cac ban cap nhat van cai de duoc. Khong commit hai file nay. Ban test co key moi nen khong cai de ban debug cu; khong tu go app cua nguoi dung.

## Gioi han can biet

- Spark/Render/Supabase/Agora deu co quota; khong phai mien phi khong gioi han. Theo doi dashboard va giu dung goi Free. Khong luu anh tren dia cung Render.
- Firebase Storage cu khong duoc chuyen du lieu tu dong sang Supabase. Anh da gui bang Storage Emulator khong co tren backend moi; gui anh moi de thu.
- App hien ho tro gui anh JPEG/PNG/WebP, khong mo rong thanh gui file tuy y trong thay doi nay.
- FCM la thong bao data-only duoc backend gui sau khi doc va kiem tra ban ghi Firestore. Phai cap quyen thong bao tren Android. Khi app o nen, nhan thong bao cuoc goi va nhan de mo man hinh tra loi; chua la tich hop Telecom/full-screen ringing. Android force-stop va han che pin cua hang co the ngan thong bao.
- Outbox thong bao luu local theo tai khoan, thu lai khi con process va khi mo app. Chua co hang doi ben ngoai dam bao giao thong bao khi ben gui bi kill ngay sau commit Firestore.
- Cuoc goi het han duoc ghi nhan boi timer server va giao dich idempotent; sau Render restart/sleep, duoc khoi phuc luc start hoac yeu cau da xac thuc tiep theo. Khong co scheduler tra phi, nen khong dam bao dung tung giay neu server dung.
- API anh kiem tra Firebase ID token + membership hien tai; gioi han 8 MiB/anh, 32 MiB/tai khoan/ngay, khong ghi de object. Khong tao URL anh public.
- Kiem tra that tren hai dien thoai khac mang: dang ky, ket ban, text/anh, unread, thong bao nen, goi audio/video, tu choi, khong tra loi, het han va goi lai. Test tu dong khong thay the kiem thu RTC/FCM that.

Nguon: [Render Free](https://render.com/docs/free), [Firebase pricing](https://firebase.google.com/pricing/), [Firebase Storage billing](https://firebase.google.com/docs/storage/faqs-storage-changes-announced-sept-2024), [Supabase pricing](https://supabase.com/pricing), [Agora pricing](https://www.agora.io/en/pricing/video-calling/).
