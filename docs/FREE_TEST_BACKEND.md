# UTEChat: backend cho nhom ban thu nghiem

Dung Firebase Spark cho Auth/Firestore/FCM, Render Free cho Node API, Supabase Free cho kho anh **private**, Agora Free cho RTC. Khong bat Blaze, Cloud Run, Cloud Functions hay tai nguyen tra phi trong huong dan nay.

## Cau hinh da trien khai (12/11/2026)

- Backend HTTPS: `https://utechat-test-backend.onrender.com`, health `/healthz`, token `/api/rtc-token`.
- Render service `utechat-test-backend`, Free, Singapore; nhanh `codex/free-test-backend`, deploy thu cong.
- Supabase project `UTEChat Test`, Free, Singapore; bucket `utechat-media` private, 8 MiB va JPEG/PNG/WebP.
- Firebase van dung project `utechat-ab9d8`; da deploy `firestore.indexes.json` cho danh sach hoi thoai va cuoc goi den. Rules khong bi noi long.
- APK de chia se: `build/UTEChat-test.apk`. **Chi gui APK, khong gui thu muc `.private`, file `.env` hay file signing properties.**

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
- Foreground dung Activity dang resume de mo man hinh goi, khong danh dau ACTIVE_CALL truoc khi Activity that su duoc tao. Khi dang ket noi, client doc lai call tu server moi 3 giay de bo sung cho listener; dung khi da ket noi/ket thuc. Timeout chi ket thuc neu transaction van thay `ringing`, giu nguyen cuoc goi da duoc tra loi.
- FCM cuoc goi giao viec xac minh Firestore cho WorkManager; Android 12+ dung expedited work, khi het quota chuyen sang job thuong. Khong de callback mang chay roi ngoai vong doi `onMessageReceived`. Worker kiem tra lai tai khoan, nguoi nhan, trang thai va han 45 giay truoc khi hien thong bao. Dang ky token thiet bi duoc dong bo lai toi da moi 6 gio khi mo app de sua du lieu dang ky cu.
- Outbox thong bao luu local theo tai khoan, thu lai khi con process va khi mo app. Chua co hang doi ben ngoai dam bao giao thong bao khi ben gui bi kill ngay sau commit Firestore.
- Cuoc goi het han duoc ghi nhan boi timer server va giao dich idempotent; sau Render restart/sleep, duoc khoi phuc luc start hoac yeu cau da xac thuc tiep theo. Khong co scheduler tra phi, nen khong dam bao dung tung giay neu server dung.
- API anh kiem tra Firebase ID token + membership hien tai; gioi han 8 MiB/anh, 32 MiB/tai khoan/ngay, khong ghi de object. Khong tao URL anh public.
- Kiem tra that tren hai dien thoai khac mang: dang ky, ket ban, text/anh, unread, thong bao nen, goi audio/video, tu choi, khong tra loi, het han va goi lai. Test tu dong khong thay the kiem thu RTC/FCM that.

## Kiem thu da thuc hien 12/11/2026

- Backend `/healthz` that tra configured, media enabled; token Agora tra HTTP 200 cho hai tai khoan.
- Hai may gia lap Android dang nhap hai tai khoan khac nhau: goi thoai ket noi ca hai phia; goi video co khung hinh remote duoc giai ma ca hai phia, sau do ket thuc dong bo.
- FCM tin nhan da hien thong bao Android khi app nhan o nen. FCM cuoc goi cung hien thong bao that; bai thu lay chinh PendingIntent cua thong bao, mo CallActivity, tra loi va xac nhan video remote. Harness cap quyen background-start khi goi PendingIntent nhu System UI; khong dung bai nay de khang dinh hanh vi cua moi giao dien thong bao/hang dien thoai.
- Hai cuoc goi audio/video khong duoc tra loi, khong chay CallActivity: server tu ghi `missed` va dung noi dung history sau 45 giay. Goi lai giao dich het han giu nguyen history ban dau.
- Anh PNG that: upload co xac thuc, commit Firestore, download trung byte; yeu cau an danh bi tu choi 401.
- Kiem tra truc quan: ten va preview cuoc goi nho in dam khi chua doc, ve chu thuong sau khi mo chat; the thoai/video cung vien va can le.
- Build release, 15 Java unit tests va lint vital deu thanh cong; APK arm64 da ky bang khoa UTEChat Tester, xac minh APK Signature Scheme v2.
- Da cai ban x86_64 release toi uu len hai gia lap (ky lai rieng bang debug key de giu du lieu thu, khong phai APK chia se). Mo app binh thuong, goi video truc tiep va goi khi app nhan o nen; nhan thong bao Android bang giao dien, bam Tra loi, thay video remote va local ca hai phia. Da ket thuc phien thu. APK arm64 chia se van giu chu ky UTEChat Tester.
- Van can nghe thu micro/loa va camera tren dien thoai that khac mang de danh gia chat luong, Bluetooth, pin va ket noi yeu. Camera gia lap chi phat canh thu nghiem.

Nguon: [Render Free](https://render.com/docs/free), [Firebase pricing](https://firebase.google.com/pricing/), [Firebase Storage billing](https://firebase.google.com/docs/storage/faqs-storage-changes-announced-sept-2024), [Supabase pricing](https://supabase.com/pricing), [Agora pricing](https://www.agora.io/en/pricing/video-calling/).

Xu ly thong bao nen theo [Firebase FCM message priority](https://firebase.google.com/docs/cloud-messaging/android-message-priority) va [Android WorkManager](https://developer.android.com/jetpack/androidx/releases/work).
