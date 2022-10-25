// Java program to describe the use
// of setLevel() function

import java.util.zip.*;
import java.io.UnsupportedEncodingException;

class GFG {

	// Function to compress the string to the given level
	static void compression(int level, String text)
		throws UnsupportedEncodingException
	{
		// deflater
		Deflater d = new Deflater(level);

		// set the Input for deflator
		d.setInput(text.getBytes("UTF-8"));

		// finish
		d.finish();

		// output bytes
		byte output[] = new byte[1024];

		// compress the data
		int size = d.deflate(output);

		// compressed String
		System.out.println("Compressed String with level ="
						+ level + " :"
						+ new String(output)
						+ "\n Size " + size);

		d.end();
	}

	// Driver code
	public static void main(String args[])
		throws UnsupportedEncodingException
	{

		// get the text
		String pattern = "GeeksforGeeks", text = "";

		// generate the text
		for (int i = 0; i < 4; i++)
			text += pattern;

		// original String
		System.out.println("Original String :" + text
						+ "\n Size " + text.length());

		// default
		compression(Deflater.DEFAULT_COMPRESSION, text);

		// no compression
		compression(Deflater.NO_COMPRESSION, text);

		// Best compression
		compression(Deflater.BEST_COMPRESSION, text);

		// Best Speed
		compression(Deflater.BEST_SPEED, text);
	}
}
