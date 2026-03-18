<<<<<<< HEAD
# GlitchDealFinder
it finds deals
=======
# Glitch Deal Finder 🚨

An Android TV application built with Jetpack Compose for TV that hunts for price glitches, pricing errors, and "penny deals" across major retailers in real-time.

## Features 🚀

-   **Real-time Scanning**: Automatically polls RSS/Atom feeds from Slickdeals, Dealnews, TechBargains, and Reddit (r/buildapcsales, r/deals).
-   **Deep Verification**: Uses Jsoup to scrape product pages (Amazon, Walmart, Best Buy, etc.) to verify current prices against historical MSRP.
-   **Glitch Detection**: Automatically flags deals with **85%+ discounts** or prices $\le$ **$0.01**.
-   **Discord Integration**: Instant push notifications to your phone via Discord Webhooks for verified glitches.
-   **TV-First UI**: High-tech "Heartbeat" dashboard optimized for D-pad navigation on Android TV / Fire TV.
-   **QR Code Checkout**: Generates a QR code for every deal so you can scan with your phone and buy instantly before the glitch is fixed.
-   **Custom Watchlist**: Add keywords to monitor for specific items (e.g., "RTX 4090", "PS5").

## Screenshots 📸
*(Add your screenshots here)*

## How to Use 🛠️

1.  **Install**: Sideload the APK onto your Android TV or Fire Stick.
2.  **Add Keywords**: Use the "Add Item" button to enter products you are hunting for.
3.  **Setup Notifications**: 
    -   Create a Discord Webhook in your server.
    -   Click the ⚙️ icon in the app and paste the URL.
4.  **Let it Run**: The bot scans every 2 minutes. When a unicorn deal is found, it will play a sound and send a notification to your phone.

## Tech Stack 💻

-   **Language**: Kotlin
-   **UI**: Jetpack Compose for TV
-   **Networking**: OkHttp, Jsoup (Scraping)
-   **Architecture**: MVVM with StateFlow
-   **Utilities**: ZXing (QR Codes)

## Disclaimer ⚠️

This tool is for educational purposes. Many retailers will cancel orders based on pricing errors. Use responsibly.

---
Built with ❤️ for deal hunters.
>>>>>>> 8d6151e (Initial commit: Glitch Deal Finder Bot)
