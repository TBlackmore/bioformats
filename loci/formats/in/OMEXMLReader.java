//
// OMEXMLReader.java
//

/*
LOCI Bio-Formats package for reading and converting biological file formats.
Copyright (C) 2005-@year@ Melissa Linkert, Curtis Rueden, Chris Allan,
Eric Kjellman and Brian Loranger.

This program is free software; you can redistribute it and/or modify
it under the terms of the GNU Library General Public License as published by
the Free Software Foundation; either version 2 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Library General Public License for more details.

You should have received a copy of the GNU Library General Public License
along with this program; if not, write to the Free Software
Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package loci.formats.in;

import java.io.*;
import java.util.*;
import java.util.zip.*;
import loci.formats.*;
import loci.formats.codec.Base64Codec;
import loci.formats.codec.CBZip2InputStream;
import loci.formats.meta.MetadataRetrieve;
import loci.formats.meta.MetadataStore;

/**
 * OMEXMLReader is the file format reader for OME-XML files.
 *
 * <dl><dt><b>Source code:</b></dt>
 * <dd><a href="https://skyking.microscopy.wisc.edu/trac/java/browser/trunk/loci/formats/in/OMEXMLReader.java">Trac</a>,
 * <a href="https://skyking.microscopy.wisc.edu/svn/java/trunk/loci/formats/in/OMEXMLReader.java">SVN</a></dd></dl>
 *
 * @author Melissa Linkert linkert at wisc.edu
 */
public class OMEXMLReader extends FormatReader {

  // -- Constants --

  private static final String NO_OME_JAVA_MSG =
    "The Java OME-XML library is required to read OME-XML files. Please " +
    "obtain ome-java.jar from http://loci.wisc.edu/ome/formats.html";

  // -- Static fields --

  private static boolean noOME = false;

  static {
    try {
      Class.forName("ome.xml.OMEXMLNode");
    }
    catch (Throwable t) {
      noOME = true;
      if (debug) LogTools.trace(t);
    }
  }

  // -- Fields --

  /** Number of bits per pixel. */
  protected int[] bpp;

  /** Offset to each plane's data. */
  protected Vector[] offsets;

  /** String indicating the compression type. */
  protected String[] compression;

  // -- Constructor --

  /** Constructs a new OME-XML reader. */
  public OMEXMLReader() { super("OME-XML", "ome"); }

  // -- IFormatReader API methods --

  /* @see loci.formats.IFormatReader#isThisType(byte[]) */
  public boolean isThisType(byte[] block) {
    String xml = new String(block);
    return xml.startsWith("<?xml") && xml.indexOf("<OME") >= 0;
  }

  /**
   * @see loci.formats.IFormatReader#openBytes(int, byte[], int, int, int, int)
   */
  public byte[] openBytes(int no, byte[] buf, int x, int y, int w, int h)
    throws FormatException, IOException
  {
    FormatTools.assertId(currentId, true, 1);
    FormatTools.checkPlaneNumber(this, no);
    FormatTools.checkBufferSize(this, buf.length, w, h);

    long offset = ((Long) offsets[series].get(no)).longValue();

    in.seek(offset);

    int len = 0;
    if (no < getImageCount() - 1) {
      len = (int) (((Long) offsets[series].get(no + 1)).longValue() - offset);
    }
    else {
      len = (int) (in.length() - offset);
    }
    String data = in.readString(len);

    // retrieve the compressed pixel data

    int dataStart = data.indexOf(">") + 1;
    String pix = data.substring(dataStart);
    if (pix.indexOf("<") > 0) {
      pix = pix.substring(0, pix.indexOf("<"));
    }
    data = null;

    Base64Codec e = new Base64Codec();
    byte[] pixels = e.base64Decode(pix);
    pix = null;

    if (compression[series].equals("bzip2")) {
      byte[] tempPixels = pixels;
      pixels = new byte[tempPixels.length - 2];
      System.arraycopy(tempPixels, 2, pixels, 0, pixels.length);

      ByteArrayInputStream bais = new ByteArrayInputStream(pixels);
      CBZip2InputStream bzip = new CBZip2InputStream(bais);
      pixels = new byte[core.sizeX[series]*core.sizeY[series]*bpp[series]];
      bzip.read(pixels, 0, pixels.length);
      tempPixels = null;
      bais.close();
      bais = null;
      bzip = null;
    }
    else if (compression[series].equals("zlib")) {
      try {
        Inflater decompressor = new Inflater();
        decompressor.setInput(pixels, 0, pixels.length);
        pixels = new byte[core.sizeX[series]*core.sizeY[series]*bpp[series]];
        decompressor.inflate(pixels);
        decompressor.end();
      }
      catch (DataFormatException dfe) {
        throw new FormatException("Error uncompressing zlib data.");
      }
    }

    int depth = FormatTools.getBytesPerPixel(core.pixelType[series]);
    for (int row=0; row<h; row++) {
      int off = (row + y) * core.sizeX[series] * depth + x * depth;
      System.arraycopy(pixels, off, buf, row * w * depth, w * depth);
    }

    return buf;
  }

  // -- IFormatHandler API methods --

  /* @see loci.formats.IFormatHandler#close() */
  public void close() throws IOException {
    super.close();
    bpp = null;
    offsets = null;
    compression = null;
  }

  // -- Internal FormatReader API methods --

  /* @see loci.formats.FormatReader#initFile(String) */
  protected void initFile(String id) throws FormatException, IOException {
    if (debug) debug("OMEXMLReader.initFile(" + id + ")");
    if (noOME) throw new FormatException(NO_OME_JAVA_MSG);
    super.initFile(id);

    in = new RandomAccessStream(id);

    in.seek(0);
    String s = in.readString((int) in.length());
    in.seek(200);

    MetadataRetrieve omexmlMeta = (MetadataRetrieve)
      MetadataTools.createOMEXMLMetadata(s);

    status("Determining endianness");

    int numDatasets = 0;
    Vector endianness = new Vector();
    Vector bigEndianPos = new Vector();

    byte[] buf = new byte[8192];
    in.read(buf, 0, 9);

    while (in.getFilePointer() < in.length()) {
      // read a block of 8192 characters, looking for the "BigEndian" pattern
      boolean found = false;
      while (!found) {
        if (in.getFilePointer() < in.length()) {
          int read = in.read(buf, 9, buf.length - 9);
          String test = new String(buf);

          int ndx = test.indexOf("BigEndian");
          if (ndx != -1) {
            found = true;
            String endian = test.substring(ndx + 11).trim();
            if (endian.startsWith("\"")) endian = endian.substring(1);
            endianness.add(new Boolean(!endian.toLowerCase().startsWith("t")));
            bigEndianPos.add(new Long(in.getFilePointer() - read - 9 + ndx));
            numDatasets++;
          }
        }
        else if (numDatasets == 0) {
          throw new FormatException("Pixel data not found.");
        }
        else found = true;
        System.arraycopy(buf, buf.length - 9, buf, 0, 9);
      }
    }

    offsets = new Vector[numDatasets];

    for (int i=0; i<numDatasets; i++) {
      offsets[i] = new Vector();
    }

    status("Finding image offsets");

    // look for the first BinData element in each series

    for (int i=0; i<numDatasets; i++) {
      in.seek(((Long) bigEndianPos.get(i)).longValue());
      boolean found = false;
      in.read(buf, 0, 14);

      while (!found) {
        if (in.getFilePointer() < in.length()) {
          int numRead = in.read(buf, 14, buf.length - 14);

          String test = new String(buf);

          int ndx = test.indexOf("<Bin");
          if (ndx == -1) {
            System.arraycopy(buf, buf.length - 14, buf, 0, 14);
          }
          else {
            while (!((ndx != -1) && (ndx != test.indexOf("<Bin:External")) &&
              (ndx != test.indexOf("<Bin:BinaryFile"))))
            {
              ndx = test.indexOf("<Bin", ndx + 1);
            }
            found = true;
            numRead += 14;
            offsets[i].add(new Long(in.getFilePointer() - (numRead - ndx)));
          }
          test = null;
        }
        else {
          throw new FormatException("Pixel data not found");
        }
      }
    }

    in.seek(0);

    for (int i=0; i<numDatasets; i++) {
      if (i == 0) {
        buf = new byte[((Long) offsets[i].get(0)).intValue()];
      }
      else {
        // look for the next Image element

        boolean found = false;
        buf = new byte[8192];
        in.read(buf, 0, 14);
        while (!found) {
          if (in.getFilePointer() < in.length()) {
            in.read(buf, 14, buf.length - 14);

            String test = new String(buf);

            int ndx = test.indexOf("<Image ");
            if (ndx == -1) {
              System.arraycopy(buf, buf.length - 14, buf, 0, 14);
            }
            else {
              found = true;
              in.seek(in.getFilePointer() - 8192 + ndx);
            }
            test = null;
          }
          else {
            throw new FormatException("Pixel data not found");
          }
        }

        int bufSize = (int) (((Long) offsets[i].get(0)).longValue() -
          in.getFilePointer());
        buf = new byte[bufSize];
      }
      in.read(buf);
    }
    buf = null;

    status("Populating metadata");

    core = new CoreMetadata(numDatasets);

    bpp = new int[numDatasets];
    compression = new String[numDatasets];

    int oldSeries = getSeries();

    for (int i=0; i<numDatasets; i++) {
      setSeries(i);

      core.littleEndian[i] = ((Boolean) endianness.get(i)).booleanValue();

      Integer w = omexmlMeta.getPixelsSizeX(i, 0);
      Integer h = omexmlMeta.getPixelsSizeY(i, 0);
      Integer t = omexmlMeta.getPixelsSizeT(i, 0);
      Integer z = omexmlMeta.getPixelsSizeZ(i, 0);
      Integer c = omexmlMeta.getPixelsSizeC(i, 0);
      String pixType = omexmlMeta.getPixelsPixelType(i, 0);
      core.currentOrder[i] = omexmlMeta.getPixelsDimensionOrder(i, 0);
      core.sizeX[i] = w.intValue();
      core.sizeY[i] = h.intValue();
      core.sizeT[i] = t.intValue();
      core.sizeZ[i] = z.intValue();
      core.sizeC[i] = c.intValue();
      core.rgb[i] = false;
      core.interleaved[i] = false;
      core.indexed[i] = false;
      core.falseColor[i] = false;

      String type = pixType.toLowerCase();
      boolean signed = type.charAt(0) != 'u';
      if (type.endsWith("16")) {
        bpp[i] = 2;
        core.pixelType[i] = signed ? FormatTools.INT16 : FormatTools.UINT16;
      }
      else if (type.endsWith("32")) {
        bpp[i] = 4;
        core.pixelType[i] = signed ? FormatTools.INT32 : FormatTools.UINT32;
      }
      else if (type.equals("float")) {
        bpp[i] = 4;
        core.pixelType[i] = FormatTools.FLOAT;
      }
      else {
        bpp[i] = 1;
        core.pixelType[i] = signed ? FormatTools.INT8 : FormatTools.UINT8;
      }

      // calculate the number of raw bytes of pixel data that we are expecting
      int expected = core.sizeX[i] * core.sizeY[i] * bpp[i];

      // find the compression type and adjust 'expected' accordingly
      in.seek(((Long) offsets[i].get(0)).longValue());
      String data = in.readString(256);

      int compressionStart = data.indexOf("Compression") + 13;
      int compressionEnd = data.indexOf("\"", compressionStart);
      if (compressionStart != -1 && compressionEnd != -1) {
        compression[i] = data.substring(compressionStart, compressionEnd);
      }
      else compression[i] = "none";

      expected /= 2;

      in.seek(((Long) offsets[i].get(0)).longValue());

      int planes = core.sizeZ[i] * core.sizeC[i] * core.sizeT[i];

      searchForData(expected, planes);
      core.imageCount[i] = offsets[i].size();
      if (core.imageCount[i] < planes) {
        // hope this doesn't happen too often
        in.seek(((Long) offsets[i].get(0)).longValue());
        searchForData(0, planes);
        core.imageCount[i] = offsets[i].size();
      }
      buf = null;
    }
    setSeries(oldSeries);
    Arrays.fill(core.orderCertain, true);

    // populate assigned metadata store with the
    // contents of the internal OME-XML metadata object
    MetadataStore store = getMetadataStore();

    MetadataTools.convertMetadata(omexmlMeta, store);
  }

  // -- Helper methods --

  /** Searches for BinData elements, skipping 'safe' bytes in between. */
  private void searchForData(int safe, int numPlanes) throws IOException {
    int iteration = 0;
    boolean found = false;
    if (offsets[series].size() > 1) {
      Object zeroth = offsets[series].get(0);
      offsets[series].clear();
      offsets[series].add(zeroth);
    }

    in.skipBytes(1);
    while (((in.getFilePointer() + safe) < in.length()) &&
      (offsets[series].size() < numPlanes))
    {
      in.skipBytes(safe);

      // look for next BinData element
      found = false;
      byte[] buf = new byte[8192];
      while (!found) {
        if (in.getFilePointer() < in.length()) {
          int numRead = in.read(buf, 20, buf.length - 20);
          String test = new String(buf);

          // datasets with small planes could have multiple sets of pixel data
          // in this block
          int ndx = test.indexOf("<Bin");
          while (ndx != -1) {
            found = true;
            if (numRead == buf.length - 20) numRead = buf.length;
            offsets[series].add(new Long(in.getFilePointer() - numRead + ndx));
            ndx = test.indexOf("<Bin", ndx+1);
          }
          test = null;
        }
        else {
          found = true;
        }
      }
      buf = null;

      iteration++;
    }
  }

}
