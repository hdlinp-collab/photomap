# 相片地圖 PhotoMap

一個安卓 App：打開就是地圖，手機裡每張帶有 GPS 位置的相片都會以縮圖標在拍攝地點上。

## 功能

- 開啟即顯示地圖（OpenStreetMap，不需要任何 API key）
- 自動掃描相簿，從相片的 EXIF 讀取拍攝位置
- 相片以縮圖標記顯示；距離太近的相片會自動合併，右上角顯示數量
- 點一下合併的標記會放大；同一地點的多張相片會以格狀列表顯示
- 點相片會用系統相簿開啟
- 掃描結果會快取，第二次開啟幾乎瞬間載入
- 右下角按鈕：「切換底圖」、「重新掃描」和「顯示全部相片」
- 底圖可選高德地圖（預設）或 OpenStreetMap。選高德地圖時，中國大陸境內的相片座標會自動從 WGS-84 轉成 GCJ-02（火星座標），與道路對齊
- 所有資料只在手機上處理，不會上傳

## 安裝

### 方法一：直接下載 APK
每次推送程式碼，GitHub Actions 都會自動編譯：
到 repo 的 **Actions** 分頁 → 點最新一次 **Build APK** → 在 Artifacts 下載 `PhotoMap-debug-apk`，
解壓後把 `.apk` 傳到手機安裝（需要允許「安裝未知來源的應用程式」）。

### 方法二：自己編譯
用 Android Studio 開啟此資料夾，按 Run；或在命令列：

```bash
./gradlew assembleDebug
# 產出：app/build/outputs/apk/debug/app-debug.apk
```

## 權限說明

- **相片和影片**：讀取相簿中的相片
- **存取媒體位置**（Android 10+）：沒有這個權限，系統會把相片裡的 GPS 資料抹除
- **網路**：下載地圖圖磚

如果第一次開啟時只「允許部分相片」，地圖上只會出現被選取的那些相片。

## 技術

Kotlin · Android SDK 26+（Android 8.0 以上）· osmdroid · AndroidX ExifInterface
