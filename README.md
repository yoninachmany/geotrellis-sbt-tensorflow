GeoTensorFlow
=======================

## Goal: label training image from [Planet Kaggle](https://github.com/azavea/raster-vision#planet-kaggle)

![Kaggle image](train_1.jpg)

```
agriculture clear primary water
```

## Quick Start

```console
git clone https://github.com/yoninachmany/geotensorflow.git
cd geotensorflow
(./inception5h/download.sh)
sbt "run-main demo.LabelImageInception inception5h train_1.jpg"
```

![Kaggle image](train_1.jpg)

```
BEST MATCH: nematode (9.63% likely)
```

## Work with Raster Vision

Follow the [Raster Vision](https://github.com/azavea/raster-vision) instructions to setup and run experiments locally.

```console
sbt "run-main demo.LabelImageRasterVision <run_name> train_1.jpg"
```

![Kaggle image](train_1.jpg)

```
agriculture artisinal_mine bare_ground blow_down clear cloudy cultivation habitation haze partly_cloudy primary road 
MATCH: agriculture (93.61% likely)
MATCH: artisinal_mine (56.18% likely)
MATCH: bare_ground (74.19% likely)
MATCH: blow_down (53.86% likely)
MATCH: clear (82.79% likely)
MATCH: cloudy (61.66% likely)
MATCH: cultivation (46.70% likely)
MATCH: habitation (96.16% likely)
MATCH: haze (33.61% likely)
MATCH: partly_cloudy (46.89% likely)
MATCH: primary (88.13% likely)
MATCH: road (55.77% likely)
```
