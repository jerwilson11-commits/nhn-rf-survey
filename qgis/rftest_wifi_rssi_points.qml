<!DOCTYPE qgis PUBLIC 'http://mrcc.com/qgis.dtd' 'SYSTEM'>
<!--
  RF Test App - Wi-Fi RSSI point symbology for QGIS.

  Categorised on the "rssi_bucket" attribute. The five buckets and their colours are the same ones
  the handset plot and the KML export use, so a session looks identical in the field, in Google
  Earth and in a QGIS print layout.

  Apply: Layer Properties > Symbology > Style (bottom left) > Load Style... > this file.
  Or right-click the layer > Properties > Symbology > Load Style.
-->
<qgis version="3.40.0" styleCategories="Symbology">
 <renderer-v2 type="categorizedSymbol" attr="rssi_bucket" forceraster="0" symbollevels="0" enableorderby="0" referencescale="-1">
  <categories>
   <category render="true" symbol="0" value="EXCELLENT" label="≥ −55 dBm  (excellent)" type="string"/>
   <category render="true" symbol="1" value="GOOD" label="−56 to −65 dBm  (good)" type="string"/>
   <category render="true" symbol="2" value="FAIR" label="−66 to −72 dBm  (fair)" type="string"/>
   <category render="true" symbol="3" value="POOR" label="−73 to −80 dBm  (poor)" type="string"/>
   <category render="true" symbol="4" value="BAD" label="&lt; −80 dBm  (unusable)" type="string"/>
   <category render="true" symbol="5" value="" label="no Wi-Fi sample" type="string"/>
  </categories>
  <symbols>
  <symbol name="0" type="marker" alpha="1" clip_to_extent="1" force_rhr="0" frame_rate="10" is_animated="0">
   <data_defined_properties>
    <Option type="Map">
     <Option name="name" type="QString" value=""/>
     <Option name="properties"/>
     <Option name="type" type="QString" value="collection"/>
    </Option>
   </data_defined_properties>
   <layer class="SimpleMarker" enabled="1" locked="0" pass="0">
    <Option type="Map">
     <Option name="angle" type="QString" value="0"/>
     <Option name="cap_style" type="QString" value="square"/>
     <Option name="color" type="QString" value="46,125,50,255"/>
     <Option name="horizontal_anchor_point" type="QString" value="1"/>
     <Option name="joinstyle" type="QString" value="bevel"/>
     <Option name="name" type="QString" value="circle"/>
     <Option name="offset" type="QString" value="0,0"/>
     <Option name="offset_map_unit_scale" type="QString" value="3x:0,0,0,0,0,0"/>
     <Option name="offset_unit" type="QString" value="MM"/>
     <Option name="outline_color" type="QString" value="30,30,30,180"/>
     <Option name="outline_style" type="QString" value="solid"/>
     <Option name="outline_width" type="QString" value="0.2"/>
     <Option name="outline_width_map_unit_scale" type="QString" value="3x:0,0,0,0,0,0"/>
     <Option name="outline_width_unit" type="QString" value="MM"/>
     <Option name="scale_method" type="QString" value="diameter"/>
     <Option name="size" type="QString" value="2.6"/>
     <Option name="size_map_unit_scale" type="QString" value="3x:0,0,0,0,0,0"/>
     <Option name="size_unit" type="QString" value="MM"/>
     <Option name="vertical_anchor_point" type="QString" value="1"/>
    </Option>
   </layer>
  </symbol>
  <symbol name="1" type="marker" alpha="1" clip_to_extent="1" force_rhr="0" frame_rate="10" is_animated="0">
   <data_defined_properties>
    <Option type="Map">
     <Option name="name" type="QString" value=""/>
     <Option name="properties"/>
     <Option name="type" type="QString" value="collection"/>
    </Option>
   </data_defined_properties>
   <layer class="SimpleMarker" enabled="1" locked="0" pass="0">
    <Option type="Map">
     <Option name="angle" type="QString" value="0"/>
     <Option name="cap_style" type="QString" value="square"/>
     <Option name="color" type="QString" value="104,159,56,255"/>
     <Option name="horizontal_anchor_point" type="QString" value="1"/>
     <Option name="joinstyle" type="QString" value="bevel"/>
     <Option name="name" type="QString" value="circle"/>
     <Option name="offset" type="QString" value="0,0"/>
     <Option name="offset_map_unit_scale" type="QString" value="3x:0,0,0,0,0,0"/>
     <Option name="offset_unit" type="QString" value="MM"/>
     <Option name="outline_color" type="QString" value="30,30,30,180"/>
     <Option name="outline_style" type="QString" value="solid"/>
     <Option name="outline_width" type="QString" value="0.2"/>
     <Option name="outline_width_map_unit_scale" type="QString" value="3x:0,0,0,0,0,0"/>
     <Option name="outline_width_unit" type="QString" value="MM"/>
     <Option name="scale_method" type="QString" value="diameter"/>
     <Option name="size" type="QString" value="2.6"/>
     <Option name="size_map_unit_scale" type="QString" value="3x:0,0,0,0,0,0"/>
     <Option name="size_unit" type="QString" value="MM"/>
     <Option name="vertical_anchor_point" type="QString" value="1"/>
    </Option>
   </layer>
  </symbol>
  <symbol name="2" type="marker" alpha="1" clip_to_extent="1" force_rhr="0" frame_rate="10" is_animated="0">
   <data_defined_properties>
    <Option type="Map">
     <Option name="name" type="QString" value=""/>
     <Option name="properties"/>
     <Option name="type" type="QString" value="collection"/>
    </Option>
   </data_defined_properties>
   <layer class="SimpleMarker" enabled="1" locked="0" pass="0">
    <Option type="Map">
     <Option name="angle" type="QString" value="0"/>
     <Option name="cap_style" type="QString" value="square"/>
     <Option name="color" type="QString" value="249,168,37,255"/>
     <Option name="horizontal_anchor_point" type="QString" value="1"/>
     <Option name="joinstyle" type="QString" value="bevel"/>
     <Option name="name" type="QString" value="circle"/>
     <Option name="offset" type="QString" value="0,0"/>
     <Option name="offset_map_unit_scale" type="QString" value="3x:0,0,0,0,0,0"/>
     <Option name="offset_unit" type="QString" value="MM"/>
     <Option name="outline_color" type="QString" value="30,30,30,180"/>
     <Option name="outline_style" type="QString" value="solid"/>
     <Option name="outline_width" type="QString" value="0.2"/>
     <Option name="outline_width_map_unit_scale" type="QString" value="3x:0,0,0,0,0,0"/>
     <Option name="outline_width_unit" type="QString" value="MM"/>
     <Option name="scale_method" type="QString" value="diameter"/>
     <Option name="size" type="QString" value="2.6"/>
     <Option name="size_map_unit_scale" type="QString" value="3x:0,0,0,0,0,0"/>
     <Option name="size_unit" type="QString" value="MM"/>
     <Option name="vertical_anchor_point" type="QString" value="1"/>
    </Option>
   </layer>
  </symbol>
  <symbol name="3" type="marker" alpha="1" clip_to_extent="1" force_rhr="0" frame_rate="10" is_animated="0">
   <data_defined_properties>
    <Option type="Map">
     <Option name="name" type="QString" value=""/>
     <Option name="properties"/>
     <Option name="type" type="QString" value="collection"/>
    </Option>
   </data_defined_properties>
   <layer class="SimpleMarker" enabled="1" locked="0" pass="0">
    <Option type="Map">
     <Option name="angle" type="QString" value="0"/>
     <Option name="cap_style" type="QString" value="square"/>
     <Option name="color" type="QString" value="239,108,0,255"/>
     <Option name="horizontal_anchor_point" type="QString" value="1"/>
     <Option name="joinstyle" type="QString" value="bevel"/>
     <Option name="name" type="QString" value="circle"/>
     <Option name="offset" type="QString" value="0,0"/>
     <Option name="offset_map_unit_scale" type="QString" value="3x:0,0,0,0,0,0"/>
     <Option name="offset_unit" type="QString" value="MM"/>
     <Option name="outline_color" type="QString" value="30,30,30,180"/>
     <Option name="outline_style" type="QString" value="solid"/>
     <Option name="outline_width" type="QString" value="0.2"/>
     <Option name="outline_width_map_unit_scale" type="QString" value="3x:0,0,0,0,0,0"/>
     <Option name="outline_width_unit" type="QString" value="MM"/>
     <Option name="scale_method" type="QString" value="diameter"/>
     <Option name="size" type="QString" value="2.6"/>
     <Option name="size_map_unit_scale" type="QString" value="3x:0,0,0,0,0,0"/>
     <Option name="size_unit" type="QString" value="MM"/>
     <Option name="vertical_anchor_point" type="QString" value="1"/>
    </Option>
   </layer>
  </symbol>
  <symbol name="4" type="marker" alpha="1" clip_to_extent="1" force_rhr="0" frame_rate="10" is_animated="0">
   <data_defined_properties>
    <Option type="Map">
     <Option name="name" type="QString" value=""/>
     <Option name="properties"/>
     <Option name="type" type="QString" value="collection"/>
    </Option>
   </data_defined_properties>
   <layer class="SimpleMarker" enabled="1" locked="0" pass="0">
    <Option type="Map">
     <Option name="angle" type="QString" value="0"/>
     <Option name="cap_style" type="QString" value="square"/>
     <Option name="color" type="QString" value="198,40,40,255"/>
     <Option name="horizontal_anchor_point" type="QString" value="1"/>
     <Option name="joinstyle" type="QString" value="bevel"/>
     <Option name="name" type="QString" value="circle"/>
     <Option name="offset" type="QString" value="0,0"/>
     <Option name="offset_map_unit_scale" type="QString" value="3x:0,0,0,0,0,0"/>
     <Option name="offset_unit" type="QString" value="MM"/>
     <Option name="outline_color" type="QString" value="30,30,30,180"/>
     <Option name="outline_style" type="QString" value="solid"/>
     <Option name="outline_width" type="QString" value="0.2"/>
     <Option name="outline_width_map_unit_scale" type="QString" value="3x:0,0,0,0,0,0"/>
     <Option name="outline_width_unit" type="QString" value="MM"/>
     <Option name="scale_method" type="QString" value="diameter"/>
     <Option name="size" type="QString" value="2.6"/>
     <Option name="size_map_unit_scale" type="QString" value="3x:0,0,0,0,0,0"/>
     <Option name="size_unit" type="QString" value="MM"/>
     <Option name="vertical_anchor_point" type="QString" value="1"/>
    </Option>
   </layer>
  </symbol>
  <symbol name="5" type="marker" alpha="1" clip_to_extent="1" force_rhr="0" frame_rate="10" is_animated="0">
   <data_defined_properties>
    <Option type="Map">
     <Option name="name" type="QString" value=""/>
     <Option name="properties"/>
     <Option name="type" type="QString" value="collection"/>
    </Option>
   </data_defined_properties>
   <layer class="SimpleMarker" enabled="1" locked="0" pass="0">
    <Option type="Map">
     <Option name="angle" type="QString" value="0"/>
     <Option name="cap_style" type="QString" value="square"/>
     <Option name="color" type="QString" value="150,150,150,255"/>
     <Option name="horizontal_anchor_point" type="QString" value="1"/>
     <Option name="joinstyle" type="QString" value="bevel"/>
     <Option name="name" type="QString" value="circle"/>
     <Option name="offset" type="QString" value="0,0"/>
     <Option name="offset_map_unit_scale" type="QString" value="3x:0,0,0,0,0,0"/>
     <Option name="offset_unit" type="QString" value="MM"/>
     <Option name="outline_color" type="QString" value="30,30,30,180"/>
     <Option name="outline_style" type="QString" value="solid"/>
     <Option name="outline_width" type="QString" value="0.2"/>
     <Option name="outline_width_map_unit_scale" type="QString" value="3x:0,0,0,0,0,0"/>
     <Option name="outline_width_unit" type="QString" value="MM"/>
     <Option name="scale_method" type="QString" value="diameter"/>
     <Option name="size" type="QString" value="2.6"/>
     <Option name="size_map_unit_scale" type="QString" value="3x:0,0,0,0,0,0"/>
     <Option name="size_unit" type="QString" value="MM"/>
     <Option name="vertical_anchor_point" type="QString" value="1"/>
    </Option>
   </layer>
  </symbol>
  </symbols>
  <source-symbol>
  <symbol name="0" type="marker" alpha="1" clip_to_extent="1" force_rhr="0" frame_rate="10" is_animated="0">
   <data_defined_properties>
    <Option type="Map">
     <Option name="name" type="QString" value=""/>
     <Option name="properties"/>
     <Option name="type" type="QString" value="collection"/>
    </Option>
   </data_defined_properties>
   <layer class="SimpleMarker" enabled="1" locked="0" pass="0">
    <Option type="Map">
     <Option name="angle" type="QString" value="0"/>
     <Option name="cap_style" type="QString" value="square"/>
     <Option name="color" type="QString" value="46,125,50,255"/>
     <Option name="horizontal_anchor_point" type="QString" value="1"/>
     <Option name="joinstyle" type="QString" value="bevel"/>
     <Option name="name" type="QString" value="circle"/>
     <Option name="offset" type="QString" value="0,0"/>
     <Option name="offset_map_unit_scale" type="QString" value="3x:0,0,0,0,0,0"/>
     <Option name="offset_unit" type="QString" value="MM"/>
     <Option name="outline_color" type="QString" value="30,30,30,180"/>
     <Option name="outline_style" type="QString" value="solid"/>
     <Option name="outline_width" type="QString" value="0.2"/>
     <Option name="outline_width_map_unit_scale" type="QString" value="3x:0,0,0,0,0,0"/>
     <Option name="outline_width_unit" type="QString" value="MM"/>
     <Option name="scale_method" type="QString" value="diameter"/>
     <Option name="size" type="QString" value="2.6"/>
     <Option name="size_map_unit_scale" type="QString" value="3x:0,0,0,0,0,0"/>
     <Option name="size_unit" type="QString" value="MM"/>
     <Option name="vertical_anchor_point" type="QString" value="1"/>
    </Option>
   </layer>
  </symbol>
  </source-symbol>
  <rotation/>
  <sizescale/>
 </renderer-v2>
 <blendMode>0</blendMode>
 <featureBlendMode>0</featureBlendMode>
 <layerGeometryType>0</layerGeometryType>
</qgis>
