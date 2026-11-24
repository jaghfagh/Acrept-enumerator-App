# Keep the JavaScript bridge methods used by the web app
-keepclassmembers class com.dunegis.acrept.enumerator.MainActivity$Bridge {
    @android.webkit.JavascriptInterface <methods>;
}
