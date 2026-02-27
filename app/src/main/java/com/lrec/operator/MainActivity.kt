val btnPlayPause = findViewById<ImageButton>(R.id.btnPlayPause)
val btnRewind = findViewById<ImageButton>(R.id.btnRewind)
val btnForward = findViewById<ImageButton>(R.id.btnForward)

btnPlayPause.setOnClickListener {
    if (player?.isPlaying == true) {
        player?.pause()
        btnPlayPause.setImageResource(R.drawable.ic_play)
    } else {
        player?.play()
        btnPlayPause.setImageResource(R.drawable.ic_pause)
    }
}

btnRewind.setOnClickListener {
    player?.seekBack()
}

btnForward.setOnClickListener {
    player?.seekForward()
}
