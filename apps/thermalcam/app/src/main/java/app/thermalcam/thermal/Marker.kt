package app.thermalcam.thermal

/** 点（十字）・最高・最低の印。位置は画面上の 0〜1 の座標 */
data class Marker(val x: Float, val y: Float, val celsius: Float)
