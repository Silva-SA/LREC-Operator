cmake_minimum_required(VERSION 3.22.1)
project("lrec_engine" LANGUAGES CXX)

# ─────────────────────────────────────────────────────────────────────
#  مكتبتنا الأصلية الكاملة: liblrec_engine.so
#  تُنفِّذ كل عمليات فك ضغط .lrec بدون أي تبعيات خارجية
#  تستخدم: zlib المدمجة في Android NDK
# ─────────────────────────────────────────────────────────────────────
add_library(
    lrec_engine
    SHARED
    lrec_engine.cpp
)

# ── مكتبات النظام المطلوبة ────────────────────────────────────────────
find_library(log-lib  log)   # تسجيل Android
# z = zlib المدمجة في كل إصدارات Android NDK

target_link_libraries(
    lrec_engine
    ${log-lib}
    z
)

# ── خيارات التحسين ───────────────────────────────────────────────────
target_compile_options(lrec_engine PRIVATE
    -O2
    -std=c++17
    -fexceptions
    -fvisibility=hidden
    -Wall
)
