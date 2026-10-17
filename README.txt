Float Apps - แอปหน้าต่างลอย (ไม่มีโฆษณา)

วิธีที่ 1: Android Studio (บนคอม)
  1. เปิดโฟลเดอร์นี้ด้วย Android Studio แล้วรอ Sync
  2. เมนู Build > Build Bundle(s) / APK(s) > Build APK(s)
  3. ไฟล์อยู่ที่ app/build/outputs/apk/debug/app-debug.apk

วิธีที่ 2: GitHub Actions (ไม่ต้องใช้คอม)
  1. สร้าง repo บน GitHub แล้วอัปโหลดไฟล์ทั้งหมด (รวมโฟลเดอร์ .github)
  2. แท็บ Actions > Build APK > Run workflow
  3. เสร็จแล้วโหลดไฟล์ FloatApps-debug-apk จากหน้า run

ติดตั้ง: เปิด "ติดตั้งจากแหล่งที่ไม่รู้จัก" แล้วแตะไฟล์ APK
ครั้งแรกที่เปิดแอป ให้สิทธิ์ "แสดงทับแอปอื่น"

อัปเดต v1.1: ถ้าติดตั้งทับเวอร์ชันเก่าไม่ได้ ให้ถอนการติดตั้งเวอร์ชันเก่าก่อน
