# clocc
Command Line OMEMO Chat Client

This is a really quick and dirty thrown together command line chat client mainly used to test [smack-omemo](https://github.com/vanitasvitae/smack-omemo). 
Use it on your own risk if you are into kinky things like dirty lines of code...

To build it, you must first clone the Smack repository and install it in your local maven repo:

```
git clone git@github.com:igniterealtime/Smack.git
cd Smack
git checkout master
gradle install
```

Next you can clone and build clocc:

```
cd ..
git clone git@github.com:vanitasvitae/clocc.git
cd clocc
gradle build
```

You can find the finished jar in `build/libs`.

A small help text will be displayed if you enter `/help` after logging in.

Have fun!
