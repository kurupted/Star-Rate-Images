Star Rate Images

Star Rate Images is an Android application that demonstrates applying star ratings to JPEG images, saving the rating to the images' metadata to be compatible with the ratings shown in Windows Explorer.

I was unable to find any Android gallery app that supports Windows-compatible ratings, so I made this. I use "Simple Gallery" for instance and you can Favorite your images, but then once images are copied to Windows, there is no way to sort out which ones I had favorited.

The application leverages XMP metadata to store and retrieve the ratings.

I hope this helps gallery app developers!


Features

    Select JPEG images from the device, OR, Share images from a gallery app to Star Rate Images.
    Apply a star rating to selected images.
    Save ratings directly into the images' metadata.
    View the list of selected images along with their current ratings.

Dependencies

    Apache Commons Imaging: Used for reading and writing XMP metadata.

