package com.example.javbrowser

data class JavVideoDetail(
    val code: String,
    val title: String,
    val coverUrl: String = "",
    val date: String,
    val duration: String,
    val maker: String,
    val series: String,
    val rating: String,
    val genres: List<String>,
    val actors: List<String>,
    val detailUrl: String
)
