/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.sedona.common.raster;

import org.geotools.coverage.CoverageFactoryFinder;
import org.geotools.coverage.grid.GridCoordinates2D;
import org.geotools.coverage.grid.GridCoverage2D;
import org.geotools.coverage.grid.GridCoverageFactory;
import org.geotools.coverage.grid.GridGeometry2D;
import org.geotools.gce.geotiff.GeoTiffWriter;
import org.geotools.geometry.DirectPosition2D;
import org.geotools.geometry.Envelope2D;
import org.geotools.geometry.jts.ReferencedEnvelope;
import org.geotools.referencing.CRS;
import org.geotools.referencing.crs.DefaultEngineeringCRS;
import org.locationtech.jts.geom.*;
import org.opengis.referencing.FactoryException;
import org.opengis.referencing.crs.CoordinateReferenceSystem;
import org.opengis.referencing.operation.TransformException;

import java.awt.image.Raster;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.function.DoublePredicate;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;

public class Functions {

    public static Geometry envelope(GridCoverage2D raster) throws FactoryException {
        Envelope2D envelope2D = raster.getEnvelope2D();

        Envelope envelope = new Envelope(envelope2D.getMinX(), envelope2D.getMaxX(), envelope2D.getMinY(), envelope2D.getMaxY());
        int srid = srid(raster);
        return new GeometryFactory(new PrecisionModel(), srid).toGeometry(envelope);
    }

    public static int numBands(GridCoverage2D raster) {
        return raster.getNumSampleDimensions();
    }

    public static GridCoverage2D setSrid(GridCoverage2D raster, int srid) throws FactoryException {
        CoordinateReferenceSystem crs;
        if (srid == 0) {
            crs = DefaultEngineeringCRS.CARTESIAN_2D;
        } else {
            crs = CRS.decode("EPSG:" + srid);
        }
        ReferencedEnvelope referencedEnvelope = new ReferencedEnvelope(raster.getEnvelope2D(), crs);
        GridCoverageFactory gridCoverageFactory = CoverageFactoryFinder.getGridCoverageFactory(null);
        return gridCoverageFactory.create(raster.getName().toString(), raster.getRenderedImage(), referencedEnvelope);
    }

    public static int srid(GridCoverage2D raster) throws FactoryException {
        CoordinateReferenceSystem crs = raster.getCoordinateReferenceSystem();
        if (crs instanceof DefaultEngineeringCRS) {
            // GeoTools defaults to internal non-standard epsg codes, like 404000, if crs is missing.
            // We need to check for this case and return 0 instead.
            if (((DefaultEngineeringCRS) crs).isWildcard()) {
                return 0;
            }
        }
        return Optional.ofNullable(CRS.lookupEpsgCode(crs, true)).orElse(0);
    }

    public static Double value(GridCoverage2D rasterGeom, Geometry geometry, int band) throws TransformException {
        return values(rasterGeom, Collections.singletonList(geometry), band).get(0);
    }

    public static List<Double> values(GridCoverage2D rasterGeom, List<Geometry> geometries, int band) throws TransformException {
        int numBands = rasterGeom.getNumSampleDimensions();
        if (band < 1 || band > numBands) {
            // Invalid band index. Return nulls.
            return geometries.stream().map(geom -> (Double) null).collect(Collectors.toList());
        }
        Raster raster = rasterGeom.getRenderedImage().getData();
        GridGeometry2D gridGeometry = rasterGeom.getGridGeometry();
        double[] noDataValues = rasterGeom.getSampleDimension(band - 1).getNoDataValues();
        DoublePredicate isNoData = d -> noDataValues != null && DoubleStream.of(noDataValues).anyMatch(noDataValue -> Double.compare(noDataValue, d) == 0);
        double[] pixelBuffer = new double[numBands];

        List<Double> result = new ArrayList<>(geometries.size());
        for (Geometry geom : geometries) {
            if (geom == null) {
                result.add(null);
            } else {
                Point point = ensurePoint(geom);
                DirectPosition2D directPosition2D = new DirectPosition2D(point.getX(), point.getY());
                GridCoordinates2D gridCoordinates2D = gridGeometry.worldToGrid(directPosition2D);
                try {
                    double pixel = raster.getPixel(gridCoordinates2D.x, gridCoordinates2D.y, pixelBuffer)[band - 1];
                    if (isNoData.test(pixel)) {
                        result.add(null);
                    } else {
                        result.add(pixel);
                    }
                } catch (ArrayIndexOutOfBoundsException exc) {
                    // Points outside the extent should return null
                    result.add(null);
                }
            }
        }
        return result;
    }

    private static Point ensurePoint(Geometry geometry) {
        if (geometry instanceof Point) {
            return (Point) geometry;
        }
        throw new IllegalArgumentException("Attempting to get the value of a pixel with a non-point geometry.");
    }
}