package com.example.temiapp

object HospitalAsrConfig {
    const val BASE_URL = "https://apigw-i.apim.hosp.ncku.edu.tw/rd/prod-i/asr/default"
    const val CLIENT_ID = "dfe354c2cc09cc5bcf4c3b6ffb84c11a"

    const val PROMPT = "護理站 配膳室 污物室 被服車 輪椅區 體重計 充電座 全區導覽 病房導覽 政策宣傳 衛教宣導 801 802 803 805 806A 806B 807A 807B 808A 808B 809A 809B 810A 810B 811A 811B 812A 812B 813A 813B 815A 815B 816A 816B 817A 817B 818A 818B 818C 819A 819B 819C 820A 820B 820C 821A 821B 821C 822A 822B 822C 823A 823B 823C 825A 825B 825C 826A 826B 826C 827"
    const val LANG = "zh"
    const val FORMAT = "txt"
    const val SOURCE = "Android"
    const val SYSTEM_NAME = "TemiApp"
    const val USER_ID = "temi01"

    const val RECORDING_MILLIS = 5000L
    const val CONNECT_TIMEOUT_MILLIS = 15000
    const val READ_TIMEOUT_MILLIS = 180000
}
