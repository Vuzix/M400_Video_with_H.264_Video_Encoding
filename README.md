# M400_Video_with_H.264_Video_Encoding

This zip file contains two Android Studio projects which each demonstrate access the H.264 
hardware video encoder in a different mode of operation of the Media Codec. One demonstrates
MediaCodec in asynchronous mode, the other in sychronous mode.

A full description of the differences in MediaCodec modes can be found at 
https://developer.android.com/reference/android/media/MediaCodec.html


VideoHWEncodingAsyncApi
-----------------------
This application demonstrates the H.264 hardware encoder using asynchronous mode of MediaCodec, 
which has been available since API 21. This implementation may be preferred for ground-up 
development.


VideoHWEncodingSyncApi
----------------------
This application demonstrates the H.264 hardware encoder using synchronous polling, which is 
supported since API 16. This implementation may be required for integration into applications 
with support for an API prior to 21.  This sample intentionally uses deprecated methods for 
the sake of backward compatibility.

