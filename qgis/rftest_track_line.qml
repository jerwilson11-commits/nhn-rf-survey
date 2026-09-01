<!DOCTYPE qgis PUBLIC 'http://mrcc.com/qgis.dtd' 'SYSTEM'>
<!--
  RF Test App - walked/driven track line.
  Neutral grey, drawn thin, meant to sit UNDER the RSSI points so the route is legible without
  competing with the measurement colours. Apply to the LineString sublayer.
-->
<qgis version="3.40.0" styleCategories="Symbology">
 <renderer-v2 type="singleSymbol" forceraster="0" symbollevels="0" enableorderby="0" referencescale="-1">
  <symbols>
   <symbol name="0" type="line" alpha="0.8" clip_to_extent="1" force_rhr="0" frame_rate="10" is_animated="0">
    <data_defined_properties>
     <Option type="Map">
      <Option name="name" type="QString" value=""/>
      <Option name="properties"/>
      <Option name="type" type="QString" value="collection"/>
     </Option>
    </data_defined_properties>
    <layer class="SimpleLine" enabled="1" locked="0" pass="0">
     <Option type="Map">
      <Option name="align_dash_pattern" type="QString" value="0"/>
      <Option name="capstyle" type="QString" value="round"/>
      <Option name="customdash" type="QString" value="5;2"/>
      <Option name="customdash_unit" type="QString" value="MM"/>
      <Option name="draw_inside_polygon" type="QString" value="0"/>
      <Option name="joinstyle" type="QString" value="round"/>
      <Option name="line_color" type="QString" value="130,130,130,255"/>
      <Option name="line_style" type="QString" value="solid"/>
      <Option name="line_width" type="QString" value="0.5"/>
      <Option name="line_width_unit" type="QString" value="MM"/>
      <Option name="offset" type="QString" value="0"/>
      <Option name="offset_unit" type="QString" value="MM"/>
      <Option name="ring_filter" type="QString" value="0"/>
      <Option name="use_custom_dash" type="QString" value="0"/>
     </Option>
    </layer>
   </symbol>
  </symbols>
  <rotation/>
  <sizescale/>
 </renderer-v2>
 <blendMode>0</blendMode>
 <featureBlendMode>0</featureBlendMode>
 <layerGeometryType>1</layerGeometryType>
</qgis>
